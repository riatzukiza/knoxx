(ns knoxx.backend.extern.approved-media-verifier
  "Disposable real CMS/publication HTTP fixture, excluded from production.
   Identity and empty evidence stores are fixtures; no model or database runs."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [knoxx.backend.extern.fastify :as fastify]
            [knoxx.backend.extern.fastify.cms-documents :as documents]
            [knoxx.backend.extern.fastify.cms-publication :as publications]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.http-server :as http]
            [knoxx.backend.infra.routes.publication-reconcile :as reconcile]
            [knoxx.backend.infra.stores.translation-evidence-registry :as evidence-registry]
            [knoxx.backend.infra.stores.translation-split-registry :as split-registry]
            [knoxx.backend.infra.translation-evidence-store :as evidence]
            [knoxx.backend.infra.translation-split-store :as splits]
            [knoxx.backend.law.publication-manifest :as manifest-law]))

(defn- sha256 [bytes]
  (.digest (.update (.createHash crypto "sha256") bytes) "hex"))

(defn- read-json [file]
  (into {} (map (fn [[key value]] [(keyword key) value]))
        (js->clj (.parse js/JSON (fs/readFileSync file "utf8")))))

(defn- source-proof! [proof-file]
  (let [proof (read-json proof-file)
        checkout (fs/realpathSync (:checkout proof))]
    (when-not (= checkout (:checkout proof))
      (throw (ex-info "Proof checkout must be physical" {})))
    (doseq [[relative expected] (:files proof)]
      (let [file (path/resolve checkout relative)]
        (when-not (and (str/starts-with? (fs/realpathSync file) (str checkout path/sep))
                       (= expected (sha256 (fs/readFileSync file))))
          (throw (ex-info "Source differs from the preflight proof" {:file relative})))))
    (when-not (= (:compiled-sha256 proof)
                 (sha256 (fs/readFileSync (aget js/process.argv 1))))
      (throw (ex-info "Serving artifact differs from the preflight proof" {})))
    proof))

(defn- authenticated? [request token]
  (some #{(str "approved_media=" token)}
        (str/split (or (aget (.-headers request) "cookie") "") #";\s*")))

(defn- route! [app method url handler]
  (fastify/route! app {:method method :url url :handler handler}))

(defn- auth-context! [app token principal]
  (route! app "GET" "/api/auth/context"
          (fn [request reply]
            (if (authenticated? request token)
              (fastify/send-json! reply 200
                                  {:user {:id (:actor-id principal) :email "media-verifier@example.invalid"
                                          :displayName "Disposable media verifier"}
                                   :org {:id (:org-id principal) :name "Disposable CMS media fixture"}
                                   :roleSlugs ["admin"] :permissions (:permissions principal)
                                   :authProvider "test-fixture"})
              (fastify/send-json! reply 403 {:error "Fixture session required"})))))

(defn- cms-routes! [app token principal config proof]
  (let [handlers {:route! route! :json-response! fastify/send-json!
                  :with-request-context! (fn [_ request _ operation]
                                           (operation (when (authenticated? request token) principal)))
                  :ensure-permission! authz/ensure-permission!}]
    (documents/register! app nil handlers)
    (publications/register-cms-publication-routes! app nil config handlers)
    (reconcile/register-publication-reconcile-routes! app nil config handlers)
    (route! app "GET" "/_verify/start/:token"
            (fn [request reply]
              (if (= token (:token (fastify/request-params request)))
                (do (.header reply "Set-Cookie"
                             (str "approved_media=" token "; HttpOnly; SameSite=Strict; Path=/"))
                    (.header reply "Cache-Control" "no-store")
                    (fastify/send-json! reply 200 {:fixture "approved-media"}))
                (fastify/send-json! reply 403 {:error "Unknown disposable session"}))))
    (route! app "GET" "/_verify/proof"
            (fn [request reply]
              (if (authenticated? request token)
                (fastify/send-json! reply 200 proof)
                (fastify/send-json! reply 403 {:error "Fixture session required"})))))
  (auth-context! app token principal))

(defn- frontend! [app directory]
  (when (seq directory)
    (let [root (fs/realpathSync directory)]
      (route! app "GET" "/*"
              (fn [request reply]
                (let [url (first (str/split (.-url request) #"\?"))
                      file (path/resolve root (str "." (if (contains? #{"/" "/cms" "/login"} url) "/index.html" url)))]
                  (if (and (str/starts-with? file (str root path/sep))
                           (not (str/starts-with? url "/api/"))
                           (fs/existsSync file) (.isFile (fs/statSync file))
                           (str/starts-with? (fs/realpathSync file) (str root path/sep)))
                    (do (.type reply (get {".js" "application/javascript" ".css" "text/css" ".html" "text/html"
                                          ".svg" "image/svg+xml"} (path/extname file) "application/octet-stream"))
                        (.send reply (fs/readFileSync file)))
                    (fastify/send-json! reply 404 {:error "Outside disposable frontend"}))))))))

(defn- current-manifest [content-root]
  (let [file (path/join content-root "manifest.edn")]
    (when (fs/existsSync file)
      (manifest-law/assert-manifest! (reader/read-string (fs/readFileSync file "utf8"))))))

(defn- public-reader! [app content-root art]
  (route! app "GET" "/graphics/Sutured_Signal.svg"
          (fn [_ reply]
            (.type reply "image/svg+xml")
            (.send reply (fs/readFileSync art))))
  (route! app "GET" "/published/manifest.edn"
          (fn [_ reply]
            (if (current-manifest content-root)
              (do (.type reply "application/edn")
                  (.send reply (fs/readFileSync (path/join content-root "manifest.edn"))))
              (fastify/send-json! reply 404 {:error "No publication committed"}))))
  (route! app "GET" "/*"
          (fn [request reply]
            (let [url (first (str/split (.-url request) #"\?"))
                  route (some #(when (= url (:route/path %)) %)
                              (:manifest/routes (current-manifest content-root)))]
              (if route
                (let [file (path/resolve content-root (:route/artifact route))]
                  (when-not (str/starts-with? (fs/realpathSync file) (str content-root path/sep))
                    (throw (ex-info "Manifest artifact escaped fixture" {})))
                  (.type reply "text/html")
                  (.send reply (str "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                                    "<meta name=\"viewport\" content=\"width=device-width\">"
                                    "<title>Approved CMS media verification</title><body>"
                                    (fs/readFileSync file "utf8") "</body></html>")))
                (fastify/send-json! reply 404 {:error "No manifest route; private data stays private"}))))))

(defn- prepare-fixture! [root art-file art-sha durable?]
  (let [art (fs/realpathSync art-file)
        content (path/join root "content")
        contracts (path/join root "contracts")
        staged-art (path/join root "assets" "Sutured_Signal.svg")
        receipt-directory (path/join root "publication-receipts")]
    (when-not (empty? (vec (fs/readdirSync root)))
      (throw (ex-info "Verifier requires its own empty directory" {})))
    (when-not (= art-sha (sha256 (fs/readFileSync art)))
      (throw (ex-info "Artwork bytes differ from the selected asset digest" {})))
    (doseq [directory (cond-> [content contracts (path/dirname staged-art)]
                       durable? (conj receipt-directory))]
      (fs/mkdirSync directory #js {:recursive true :mode 448}))
    (fs/copyFileSync art staged-art)
    (aset js/process.env "KNOXX_PUBLICATION_CONTENT_ROOT" content)
    (aset js/process.env "KNOXX_GENERATED_CONTRACTS_DIR" contracts)
    (reset! evidence-registry/store* (evidence/memory-store))
    (reset! split-registry/store* (splits/memory-store sha256))
    (cond-> {:content content :art staged-art
     :config {:contracts-dir contracts :generated-contracts-dir contracts
              :publication-content-root content
              :session-project-name "approved-media-fixture"}}
              durable? (assoc-in [:config :publication-receipt-ledger-directory] receipt-directory))))

(defn- write-receipt! [root cms public token org art-sha proof durable?]
  (let [origin (str "http://127.0.0.1:" (.-port (.address (.-server cms))))
        public-origin (str "http://127.0.0.1:" (.-port (.address (.-server public))))]
    (fs/writeFileSync (path/join root "receipt.json")
                     (.stringify js/JSON
                                 (clj->js {:origin origin :public-origin public-origin
                                           :start-url (str origin "/_verify/start/" token)
                                           :org org :session-token token :durable-receipts durable?
                                           :process-id (.-pid js/process)
                                           :asset-sha256 art-sha :proof proof}))
                     #js {:mode 384})))

(defn- restart-fixture!
  "Reopen only a prior owned durable fixture; never seed or reconcile here."
  [root proof art-sha]
  (let [prior (read-json (path/join root "receipt.json"))
        content (path/join root "content")
        contracts (path/join root "contracts")
        art (path/join root "assets" "Sutured_Signal.svg")
        receipts (path/join root "publication-receipts")
        prior-proof (into {} (map (fn [[key value]] [(keyword key) value])) (:proof prior))]
    (when-not (and (true? (:durable-receipts prior)) (= proof prior-proof)
                   (= art-sha (:asset-sha256 prior))
                   (= art-sha (sha256 (fs/readFileSync art)))
                   (string? (:org prior)) (string? (:session-token prior))
                   (every? #(.isDirectory (fs/statSync %)) [content contracts receipts]))
      (throw (ex-info "Restart requires the exact prior owned durable fixture and source proof" {})))
    (aset js/process.env "KNOXX_PUBLICATION_CONTENT_ROOT" content)
    (aset js/process.env "KNOXX_GENERATED_CONTRACTS_DIR" contracts)
    (reset! evidence-registry/store* (evidence/memory-store))
    (reset! split-registry/store* (splits/memory-store sha256))
    {:content content :art art :token (:session-token prior) :org (:org prior)
     :config {:contracts-dir contracts :generated-contracts-dir contracts
              :publication-content-root content :session-project-name "approved-media-fixture"
              :publication-receipt-ledger-directory receipts}}))

(defn- ^:async serve! [run-root proof-file art-file art-sha frontend mode]
  (let [root (fs/realpathSync run-root)
        proof (source-proof! proof-file)
        durable? (contains? #{"--durable" "--durable-restart"} mode)
        fixture (if (= mode "--durable-restart")
                  (restart-fixture! root proof art-sha)
                  (prepare-fixture! root art-file art-sha durable?))
        {:keys [config content art]} fixture
        token (or (:token fixture) (str (random-uuid)))
        org (or (:org fixture) (str "media-verify-" (random-uuid)))
        principal {:org-id org :actor-id "approved-media-verifier"
                   :permissions ["org.publications.read" "org.publications.manage"]}
        cms (http/create-app!)
        public (http/create-app!)]
    (cms-routes! cms token principal config proof)
    (frontend! cms frontend)
    (public-reader! public content art)
    (await (http/listen! cms "127.0.0.1" 0))
    (await (http/listen! public "127.0.0.1" 0))
    (write-receipt! root cms public token org art-sha proof durable?)
    (println "Disposable approved-media CMS and public reader ready")))

(defn ^:async main
  "Serve only an owned disposable directory until its shell terminates it."
  [& args]
  (try
    (when-not (and (contains? #{5 6} (count args))
                   (or (= 5 (count args)) (contains? #{"--durable" "--durable-restart"} (last args))))
      (throw (ex-info "Expected owned run root, proof, selected SVG, digest, frontend and optional durable mode" {})))
    (await (apply serve! (if (= 5 (count args)) (conj (vec args) nil) args)))
    (catch :default error
      (js/console.error error)
      (js/process.exit 1))))
