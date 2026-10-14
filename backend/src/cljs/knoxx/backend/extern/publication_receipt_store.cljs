(ns knoxx.backend.extern.publication-receipt-store
  "Node namespace, digest and inode boundary for publication receipt storage."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as native-fs]
            ["node:path" :as path]
            [clio.extern.js.fs :as clio-fs]
            [knoxx.backend.law.publication-receipt-store :as law]))

(defn sha256-hex
  "Hash exact UTF-8 payload bytes; a namespace digest is only a path locator."
  [text]
  (.digest (.update (.createHash crypto "sha256") text "utf8") "hex"))

(defn- assert-owned-directory!
  "Refuse a symlink or non-directory inside the trusted physical root."
  [directory]
  (when-not (and (.isDirectory (native-fs/lstatSync directory))
                 (= directory (native-fs/realpathSync directory)))
    (throw (ex-info "Publication receipt partition must be an owned physical directory"
                    {:cause :publication-receipt/invalid-partition}))))

(defn namespace-paths
  "Resolve an existing absolute data root and its exact trusted scoped child."
  [directory scope]
  (law/assert-directory! directory)
  (law/assert-scope! scope)
  (when-not (and (path/isAbsolute directory) (.isDirectory (native-fs/statSync directory)))
    (throw (ex-info "Publication receipt root must be an existing absolute directory"
                    {:cause :publication-receipt/unavailable-root})))
  (let [root (native-fs/realpathSync directory)
        partition (path/join root (sha256-hex (pr-str [(:org-id scope) (:project scope)])))]
    (when (native-fs/existsSync partition) (assert-owned-directory! partition))
    (let [schemas (path/join partition "schemas")]
      (when (native-fs/existsSync schemas) (assert-owned-directory! schemas))
      {:root root :directory partition :file (path/join partition "events.edn")
       :schemas schemas})))

(defn- identity-data
  "Decode native bigint device/inode values without lossy numeric conversion."
  [stat]
  {:device (.toString (.-dev stat)) :inode (.toString (.-ino stat))})

(defn file-identity
  "Read an existing regular ledger inode and refuse symlink substitution."
  [file]
  (let [stat (native-fs/lstatSync file #js {:bigint true})]
    (when-not (.isFile stat)
      (throw (ex-info "Publication receipt ledger must be a regular owned file"
                      {:cause :publication-receipt/invalid-ledger-file})))
    (identity-data stat)))

(defn assert-file-identity!
  "Refuse a replaced or missing ledger instead of acknowledging another inode."
  [file expected]
  (when-not (= expected (file-identity file))
    (throw (ex-info "Publication receipt ledger inode changed"
                    {:cause :publication-receipt/changed-ledger-inode})))
  expected)

(defn sync-existing!
  "Sync an exact replay on its original inode through public Clio primitives."
  [file expected]
  (let [lock (clio-fs/acquire-lock! file)]
    (try
      (when-not (= expected (identity-data (native-fs/fstatSync (:lock/fd lock) #js {:bigint true})))
        (throw (ex-info "Publication receipt retry locked a different inode"
                        {:cause :publication-receipt/changed-ledger-inode})))
      (clio-fs/append-locked-text! lock "")
      (finally (clio-fs/release-lock! lock))))
  (assert-file-identity! file expected))
