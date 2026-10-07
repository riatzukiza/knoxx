(ns verify-native-music-image
  "Build or inspect an isolated image and execute its actual native music engine."
  (:require [clojure.string :as str]
            [nbb.core :as nbb]
            ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (.resolve path (.dirname path nbb/*file*) ".."))
(def backend (.join path root "backend"))

(defn require!
  "Reject an invalid observed value with its named verification condition."
  [condition description]
  (when-not condition (throw (ex-info description {}))))

(defn command!
  "Execute bounded local commands; retain actual failure output and exit nonzero."
  [program arguments options]
  (let [result (.spawnSync child program (clj->js arguments)
                          (clj->js (merge {:cwd root :encoding "utf8" :timeout 45000
                                           :maxBuffer 2097152} options)))]
    (when-not (= 0 (.-status result))
      (when-let [output (.-stdout result)] (.write js/process.stdout output))
      (when-let [output (.-stderr result)] (.write js/process.stderr output))
      (throw (ex-info (str program " failed: " (or (.-status result) (some-> result .-error .-code))) {})))
    (.-stdout result)))

(defn sha256
  "Hash the exact bytes of one local artifact."
  [file]
  (.digest (.update (.createHash crypto "sha256") (.readFileSync fs file)) "hex"))

(defn regular-tree!
  "Refuse symlinks and special files before copying declared image inputs."
  [file]
  (let [stat (.lstatSync fs file)]
    (require! (or (.isFile stat) (.isDirectory stat)) (str "Regular image input required: " file))
    (when (.isDirectory stat)
      (doseq [name (js->clj (.readdirSync fs file))]
        (regular-tree! (.join path file name))))))

(defn build-image!
  "Build only Dockerfile COPY inputs and a production artifact in an owned context."
  [image revision]
  (let [context (.mkdtempSync fs (.join path (.tmpdir os) "knoxx-native-image-build-"))]
    (try
      (doseq [relative ["Dockerfile" "package.json" "dist" "docker" "scripts/synthesize-music.mjs"]]
        (let [source (.join path backend relative)
              destination (.join path context relative)]
          (regular-tree! source)
          (.mkdirSync fs (.dirname path destination) #js {:recursive true})
          (.cpSync fs source destination #js {:recursive true :dereference false})))
      (command! "docker" ["build" "--progress=plain" "--label"
                          (str "org.opencontainers.image.revision=" revision)
                          "-t" image context]
                {:timeout 1200000 :stdio "inherit"})
      (finally (.rmSync fs context #js {:recursive true :force true})))))

(def engine-driver
  "const fs=require('node:fs'),crypto=require('node:crypto'),cp=require('node:child_process');
   if(process.getuid()!==1000||process.getgid()!==1000)throw Error('UID/GID must be1000');
   const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
   console.log(JSON.stringify({uid:process.getuid(),gid:process.getgid(),engineSha256:hash('/app/scripts/synthesize-music.mjs'),serverSha256:hash('/app/dist/server.js')}));
   const r=cp.spawnSync(process.execPath,['/app/scripts/synthesize-music.mjs','/proof/spec.json','/proof/render.wav'],{encoding:'utf8',timeout:30000});
   process.stdout.write(r.stdout||'');process.stderr.write(r.stderr||'');
   if(r.error)console.error(r.error.message);
   process.exit(r.status===null?1:r.status);")

(defn image-proof!
  "Check image identity and hashes, then require real metadata and nonzero PCM."
  [image revision]
  (let [info (js->clj (js/JSON.parse (command! "docker" ["image" "inspect" "--format" "{{json .}}" image] {}))
                     :keywordize-keys true)
        image-id (:Id info)
        fixture (.mkdtempSync fs (.join path (.tmpdir os) "knoxx-native-image-proof-"))]
    (try
      (require! (#{"1000" "1000:1000" "node"} (get-in info [:Config :User])) "Image must declare a non-root runtime user")
      (require! (= revision (get-in info [:Config :Labels :org.opencontainers.image.revision])) "Image revision must match checkout HEAD")
      (.chmodSync fs fixture 511)
      (.writeFileSync fs (.join path fixture "spec.json")
                      (js/JSON.stringify
                       (clj->js {:bpm 120 :duration 0.5
                                 :tracks [{:instrument "synth" :waveform "sine"
                                           :notes [{:note "A4" :time 0 :duration 0.25}]}]})))
      (let [output (command! "docker"
                            ["run" "--rm" "--network" "none" "--user" "1000:1000"
                             "--read-only" "--cap-drop" "ALL" "--security-opt" "no-new-privileges"
                             "--pids-limit" "128" "--cpus" "1" "--memory" "768m"
                             "--tmpfs" "/tmp:rw,noexec,nosuid,size=32m"
                             "--mount" (str "type=bind,src=" fixture ",dst=/proof")
                             "--entrypoint" "node" image-id "-e" engine-driver] {})
            lines (str/split-lines output)]
        (require! (= 2 (count lines)) "Require image identity and engine JSON records")
        (let [[identity metadata] (map #(js->clj (js/JSON.parse %) :keywordize-keys true) lines)
              wav-file (.join path fixture "render.wav")
              wav (.readFileSync fs wav-file)]
          (require! (= [1000 1000] [(:uid identity) (:gid identity)]) "Actual process UID/GID must be1000")
          (require! (= (sha256 (.join path backend "scripts/synthesize-music.mjs")) (:engineSha256 identity)) "Packaged engine hash must match source")
          (require! (= (sha256 (.join path backend "dist/server.js")) (:serverSha256 identity)) "Packaged server hash must match the production artifact")
          (require! (= {:ok true :durationSec 0.5 :sampleRate 44100 :channels 2 :samples 22050}
                       (select-keys metadata [:ok :durationSec :sampleRate :channels :samples]))
                    "Require actual successful half-second stereo44.1kHz metadata")
          (require! (and (>= (.-length wav) 44)
                         (= "RIFF" (.toString wav "ascii" 0 4))
                         (= "WAVE" (.toString wav "ascii" 8 12))
                         (= "fmt " (.toString wav "ascii" 12 16))
                         (= 16 (.readUInt32LE wav 16))
                         (= 1 (.readUInt16LE wav 20))
                         (= 2 (.readUInt16LE wav 22))
                         (= 44100 (.readUInt32LE wav 24))
                         (= 16 (.readUInt16LE wav 34))
                         (= "data" (.toString wav "ascii" 36 40))
                         (= 88200 (.readUInt32LE wav 40))
                         (= 88244 (.-length wav))) "Require real RIFF/WAVE stereo16-bit PCM matching metadata")
          (require! (some #(not (zero? (.readInt16LE wav %))) (range 44 (.-length wav) 2)) "Require nonzero PCM samples")
          (println "PASS" (pr-str {:revision revision :image-id image-id :uid 1000 :gid 1000
                                   :engine-sha256 (:engineSha256 identity)
                                   :server-sha256 (:serverSha256 identity)
                                   :wav-sha256 (sha256 wav-file) :wav-bytes (.-length wav)
                                   :metadata metadata}))
          (println "WARN Image proof is not live MCP, deployment, availability or artifact-quality qualification.")))
      (finally (.rmSync fs fixture #js {:recursive true :force true})))))

(defn -main
  "Require an explicit image selection; return a failing status for any proof gap."
  [& arguments]
  (try
    (let [[mode image] arguments]
      (require! (and (= 2 (count arguments)) (#{"--image" "--build"} mode)
                     (seq image) (not (str/starts-with? image "-")) (not (re-find #"\s" image)))
                "Usage: nbb scripts/verify_native_music_image.cljs --image|--build IMAGE")
      (let [revision (str/trim (command! "git" ["rev-parse" "HEAD"] {}))]
        (require! (re-matches #"[0-9a-f]{40}" revision) "Require a full checkout Git revision")
        (require! (str/blank? (command! "git" ["diff" "--name-only" "HEAD" "--"
                                              "backend/Dockerfile" "backend/package.json"
                                              "backend/scripts/synthesize-music.mjs"] {}))
                  "Commit image recipe and native source before image proof")
        (when (= "--build" mode) (build-image! image revision))
        (image-proof! image revision)))
    0
    (catch :default error (println "FAIL" (ex-message error)) 1)))

(when (= nbb/*file* (nbb/invoked-file))
  (set! (.-exitCode js/process) (apply -main *command-line-args*)))
