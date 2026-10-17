(ns knoxx.backend.infra.routes.translation-review-characterization-test
  "Public-facade compatibility controls expected to pass on the original source."
  (:require [cljs.test :as t]
            [knoxx.backend.extern.translation-review-facade-fixture :as f]
            [knoxx.backend.infra.routes.translation-review :as facade]
            [knoxx.backend.infra.translation-evidence-store :as evidence]
            [knoxx.backend.infra.translation-split-store :as splits]))

(def ^:private golden
  "Frozen current-law UTF-8 vectors; never computed by the extraction under test."
  {:set-id "translation-candidate-set-3602cdac7d336766c35e3197dc21050ba0000ea487516f791bd9937c73be27a1"
   :split-ids ["translation-split-9db711a454a7504775bb02176caca2d4f677a095139dfaeb4e0a1330c720ce6d"
               "translation-split-c8221d6b4886f56b7e52641afbd410e4cb75c75a12e4eed4a650aeef1c57f839"]
   :single-inputs ["[\"translation-candidate-set-3602cdac7d336766c35e3197dc21050ba0000ea487516f791bd9937c73be27a1\" \"translation-split-9db711a454a7504775bb02176caca2d4f677a095139dfaeb4e0a1330c720ce6d\" [\"reviewer-1\" nil nil] [\"good\" \"good\" \"correct\" \"safe\" \"approve\" \"¡Primera corregida!\\n\" \"nota λ\\nsegunda\"] nil]"
                   "[\"translation-candidate-set-3602cdac7d336766c35e3197dc21050ba0000ea487516f791bd9937c73be27a1\" \"translation-split-9db711a454a7504775bb02176caca2d4f677a095139dfaeb4e0a1330c720ce6d\" [\"reviewer-1\" nil nil] [\"adequate\" \"adequate\" \"minor_errors\" \"safe\" \"reject\" \"¡Primera corregida!\\n\" \"nota λ\\nsegunda\"] \"translation-review-173823897069d4e483a8dc1580d4eee7e949cf047b51d5b871528ce0aecedb18\"]"
                   "[\"translation-candidate-set-3602cdac7d336766c35e3197dc21050ba0000ea487516f791bd9937c73be27a1\" \"translation-split-9db711a454a7504775bb02176caca2d4f677a095139dfaeb4e0a1330c720ce6d\" [\"reviewer-1\" nil nil] [\"good\" \"good\" \"correct\" \"safe\" \"approve\" \"¡Primera corregida!\\n\" \"nota λ\\nsegunda\"] \"translation-review-8eba5d48921cadeb8041d21135c90b90a91593295373978158a7bcf4dba74847\"]"]
   :single-operations ["publication-split-review-fedc1c701906aafad3463ecc3717905798d2e3d0039c3939a707f92133b95e00"
                       "publication-split-review-d4c729a4bb6869fe2b74ee3942fb04b6d7b2d2d9f16d2eb38980d79e2045c0f8"
                       "publication-split-review-d94c9361adca731db1627e6eb47cd0243404010b3fb75eed34976bd7329610ff"]
   :bulk-input "[\"translation-candidate-set-3602cdac7d336766c35e3197dc21050ba0000ea487516f791bd9937c73be27a1\" [\"reviewer-1\" nil nil] [[\"translation-split-9db711a454a7504775bb02176caca2d4f677a095139dfaeb4e0a1330c720ce6d\" [\"good\" \"good\" \"correct\" \"safe\" \"approve\" nil \"todos λ\\n\"] nil] [\"translation-split-c8221d6b4886f56b7e52641afbd410e4cb75c75a12e4eed4a650aeef1c57f839\" [\"good\" \"good\" \"correct\" \"safe\" \"approve\" nil \"todos λ\\n\"] nil]]]"
   :bulk-group "publication-split-review-bulk-82d1b838f8382453ead90c9b23282b7641ba4fe7d4a05a8b4622f1a0e7f58f91"})


(defn- values-for
  "Read one observable effect family from the complete trace."
  [ctl tag]
  (mapv second (filter #(= tag (first %)) (f/events ctl))))

(defn- tags
  "Retain causal ordering while omitting individual effect payloads."
  [ctl]
  (mapv first (f/events ctl)))

(defn- reset-observations!
  "Clear observations only; all actual durable store facts remain present."
  [ctl]
  (reset! (:events ctl) [])
  (reset! (:digests ctl) []))

(defn- split-request
  "Address the first real persisted member through the public request contract."
  [fixture status]
  {:split-review/candidate-set-id (get-in fixture [:context :candidate-set :candidate-set/id])
   :split-review/split-id (first (:split-ids golden))
   :split-review/status status
   :split-review/corrected-text "¡Primera corregida!\n"
   :review/editor-notes "nota λ\nsegunda"})

(defn- bulk-request
  "Address a complete candidate set without a whole-document correction."
  []
  {:split-review/candidate-set-id (:set-id golden)
   :split-review/status :approved :review/editor-notes "todos λ\n"})

(defn- ^:async histories!
  "Read durable histories directly, outside the traced facade delegate."
  [fixture]
  (loop [remaining (:split-ids golden) result []]
    (if-let [split-id (first remaining)]
      (recur (next remaining)
             (conj result (await (splits/review-history-for-split!
                                 (:raw-splits fixture) (:set-id golden) split-id))))
      result)))

(t/deftest ^:async approval-preconditions-precede-receipt-access
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        deps (f/deps fixture)]
    (try
      (let [missing (await (f/outcome! #(facade/approve-translation! deps f/scope {})))
            invalid (await (f/outcome!
                            #(facade/approve-translation!
                              (assoc deps :authorize-approval! (partial f/authorize! ctl))
                              f/scope {})))]
        (t/is (= {:status 503 :code "translation_approval_admission_unavailable"}
                 (ex-data (:error missing))))
        (t/is (= :translation-review/request (:contract (ex-data (:error invalid)))))
        (t/is (empty? (f/events ctl)))
        (t/is (empty? (await (evidence/approvals! (:raw-evidence fixture) f/scope)))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async approval-waits-for-content-admission-before-clock-and-write
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        deps (assoc (f/deps fixture) :authorize-approval! (partial f/authorize! ctl))]
    (f/hold! ctl :authorize nil)
    (try
      (let [task (f/start! ctl #(facade/approve-translation! deps f/scope (f/approval-request)))]
        (await (f/await-events! ctl :authorize 1))
        (t/is (= [:receipts :receipts-done :authorize] (tags ctl)))
        (t/is (empty? (await (evidence/approvals! (:raw-evidence fixture) f/scope))))
        (f/release! ctl :authorize nil)
        (t/is (= :recorded (get-in (await task) [:value :approval/status])))
        (t/is (= [:receipts :receipts-done :authorize :authorize-done
                  :clock :approval :approval-done] (tags ctl)))
        (t/is (= 1 (count (await (evidence/approvals! (:raw-evidence fixture) f/scope))))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async content-admission-rejection-preserves-error-and-skips-clock-and-write
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        failure (f/error "content admission failed")
        deps (assoc (f/deps fixture) :authorize-approval! (partial f/authorize! ctl))]
    (f/fail! ctl :authorize nil failure)
    (try
      (let [result (await (f/outcome! #(facade/approve-translation!
                                      deps f/scope (f/approval-request))))]
        (t/is (identical? failure (:error result)))
        (t/is (= [:receipts :receipts-done :authorize] (tags ctl)))
        (t/is (empty? (await (evidence/approvals! (:raw-evidence fixture) f/scope)))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async genuine-approval-refusal-writes-nothing
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        deps (assoc (f/deps fixture) :receipts-snapshot []
                    :authorize-approval! (partial f/authorize! ctl))]
    (try
      (let [result (await (facade/approve-translation! deps f/scope (f/approval-request)))]
        (t/is (= :translation-receipt-missing
                 (get-in result [:approval/refusal :refusal/type])))
        (t/is (empty? (f/events ctl)))
        (t/is (empty? (await (evidence/approvals! (:raw-evidence fixture) f/scope)))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async present-empty-snapshot-suppresses-the-populated-receipt-store
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        deps (assoc (f/deps fixture) :receipts-snapshot [])]
    (try
      (let [result (await (facade/reviewable-translations! deps f/scope))]
        (t/is (= [:evidence_missing] (mapv :work_state (:reviews result))))
        (t/is (= [false] (mapv :candidate_present (:reviews result))))
        (t/is (= [:approvals :approvals-done :dispatch :dispatch-done] (tags ctl)))
        (t/is (empty? (values-for ctl :receipts))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async snapshot-transform-runs-before-exact-tenant-and-project-filter
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        foreign (assoc (f/receipt) :translation/org-id "foreign" :translation/project "other")
        transform (fn [rows]
                    (f/event! ctl :transform rows)
                    [(assoc (first rows) :translation/org-id "org-1"
                            :translation/project "knoxx-session")
                     (assoc (f/receipt) :translation/project "other"
                            :translation/revision "foreign-newest"
                            :translation/at "2026-09-02T10:01:00.000Z")
                     (assoc (f/receipt) :translation/org-id "foreign"
                            :translation/revision "foreign-org-newest"
                            :translation/at "2026-09-02T10:01:00.000Z")])]
    (try
      (let [result (await (facade/reviewable-translations!
                          (assoc (f/deps fixture) :receipts-snapshot [foreign]
                                 :receipts-transform transform) f/scope))]
        (t/is (= [[foreign]] (values-for ctl :transform)))
        (t/is (= ["candidate-review-v1"] (mapv :translation_revision (:reviews result))))
        (t/is (= [:transform :approvals :approvals-done :dispatch :dispatch-done] (tags ctl))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async inventory-loads-approvals-before-dispatch-and-attaches-splits-last
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        key (f/dispatch-key :es)]
    (f/hold! ctl :dispatch key)
    (try
      (let [task (f/start! ctl #(facade/reviewable-translations! (f/deps fixture) f/scope))]
        (await (f/await-events! ctl :dispatch 1))
        (t/is (= [:receipts :receipts-done :approvals :approvals-done :dispatch] (tags ctl)))
        (f/release! ctl :dispatch key)
        (t/is (= :pending-review
                 (get-in (await task) [:value :reviews 0 :split_review :status])))
        (t/is (= [:receipts :receipts-done :approvals :approvals-done
                  :dispatch :dispatch-done :candidate-set :candidate-set-done
                  :turn :turn-done :history :history-done :history :history-done]
                 (tags ctl))))
      (finally (await (f/cleanup! ctl))))))

(defn- ^:async inventory-fixture!
  "Seed distinct actual dispatch outcomes and install all three read gates."
  []
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)]
    (await (f/seed-dispatch! fixture :de :dispatch/failed))
    (await (f/seed-dispatch! fixture :fr :dispatch/rejected))
    (doseq [locale [:de :es :fr]] (f/hold! ctl :dispatch (f/dispatch-key locale)))
    fixture))

(t/deftest ^:async dispatch-reads-start-eagerly-and-reverse-completion-retains-row-order
  (let [fixture (await (inventory-fixture!))
        ctl (:control fixture)
        deps (merge (f/deps fixture) (f/resources [:fr :es :de]) {:receipts-snapshot []})]
    (try
      (let [task (f/start! ctl #(facade/reviewable-translations! deps f/scope))]
        (await (f/await-events! ctl :dispatch 3))
        (t/is (= (mapv f/dispatch-key [:de :es :fr]) (values-for ctl :dispatch)))
        (t/is (empty? (values-for ctl :dispatch-done)))
        (doseq [[index locale] (map-indexed vector [:fr :es :de])]
          (f/release! ctl :dispatch (f/dispatch-key locale))
          (await (f/await-events! ctl :dispatch-done (inc index))))
        (let [result (:value (await task))]
          (t/is (= [:de :es :fr] (mapv :locale (:reviews result))))
          (t/is (= [:failed :evidence_missing :rejected] (mapv :work_state (:reviews result))))
          (t/is (= (mapv f/dispatch-key [:fr :es :de]) (values-for ctl :dispatch-done)))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async dispatch-rejection-preserves-error-and-cleanup-drains-started-branches
  (let [fixture (await (inventory-fixture!))
        ctl (:control fixture)
        failure (f/error "first ordered dispatch lookup failed")
        later-failure (f/error "later dispatch lookup failed first")
        deps (merge (f/deps fixture) (f/resources [:de :es :fr]) {:receipts-snapshot []})]
    (try
      (let [task (f/start! ctl #(facade/reviewable-translations! deps f/scope))]
        (await (f/await-events! ctl :dispatch 3))
        (f/reject! ctl :dispatch (f/dispatch-key :es) later-failure)
        (f/reject! ctl :dispatch (f/dispatch-key :de) failure)
        (t/is (identical? failure (:error (await task))))
        (t/is (empty? (values-for ctl :dispatch-done)))
        (await (f/cleanup! ctl))
        (t/is (= #{(f/dispatch-key :fr)}
                 (set (values-for ctl :dispatch-done)))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async unresolved-source-does-not-start-a-dispatch-lookup
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        deps (assoc (f/deps fixture) :receipts-snapshot [] :source-revisions {})]
    (try
      (let [result (await (facade/reviewable-translations! deps f/scope))]
        (t/is (= [:revision_unresolved] (mapv :work_state (:reviews result))))
        (t/is (empty? (values-for ctl :dispatch))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async single-operation-bytes-equal-retry-and-verdict-cycle
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        deps (f/deps fixture)
        approve (split-request fixture :approved)]
    (try
      (let [first-result (await (facade/record-split-review! deps f/scope approve))
            retried (await (facade/record-split-review! deps f/scope approve))
            rejected (await (facade/record-split-review!
                             deps f/scope (split-request fixture :rejected)))
            cycled (await (facade/record-split-review! deps f/scope approve))
            receipts (mapv :split-review/receipt [first-result rejected cycled])]
        (t/is (= (:single-inputs golden) (f/operation-inputs ctl)))
        (t/is (= (:single-operations golden) (mapv :review/operation-id receipts)))
        (t/is (= (:split-review/receipt first-result) (:split-review/receipt retried)))
        (t/is (= [:recorded :existing :recorded :recorded]
                 (mapv :split-review/status [first-result retried rejected cycled])))
        (t/is (= [3 0] (mapv count (await (histories! fixture)))))
        (t/is (= 4 (count (values-for ctl :project)))))
      (finally (await (f/cleanup! ctl))))))

(defn- ^:async release-bulk-plans!
  "Prove concurrent plans finish before the first append can begin."
  [ctl]
  (await (f/await-events! ctl :history 2))
  (t/is (= (:split-ids golden) (values-for ctl :history)))
  (t/is (= [nil] (values-for ctl :clock)))
  (t/is (= [:clock :history :history]
           (filterv #{:clock :history} (tags ctl))))
  (f/release! ctl :history (second (:split-ids golden)))
  (await (f/await-events! ctl :history-done 1))
  (t/is (empty? (values-for ctl :append)))
  (f/release! ctl :history (first (:split-ids golden)))
  (await (f/await-events! ctl :append 1))
  (t/is (= 2 (count (values-for ctl :history-done)))))

(defn- ^:async assert-complete-replay!
  "A semantic replay samples once and projects once without a new group or append."
  [fixture]
  (let [ctl (:control fixture)]
    (reset-observations! ctl)
    (let [result (await (facade/record-candidate-set-review!
                        (f/deps fixture) f/scope (bulk-request)))]
      (t/is (= :existing (:split-review/status result)))
      (t/is (= [nil] (values-for ctl :clock)))
      (t/is (empty? (values-for ctl :append)))
      (t/is (empty? (f/operation-inputs ctl)))
      (t/is (= 1 (count (values-for ctl :project))))
      (t/is (= [1 1] (mapv count (await (histories! fixture))))))))

(defn- assert-projector-arguments!
  "Bind the injected projection to the same stores, content root, hash, and clock."
  [fixture]
  (let [[set-id params] (first (values-for (:control fixture) :project))
        store-keys [:split-store :evidence-store :digest-hex]]
    (t/is (= (:set-id golden) set-id))
    (t/is (= #{:split-store :evidence-store :content-root :digest-hex :clock}
             (set (keys params))))
    (t/is (= (select-keys fixture store-keys) (select-keys params store-keys)))
    (t/is (= "/translated" (:content-root params)))
    (t/is (fn? (:clock params)))))

(t/deftest ^:async bulk-clock-plans-group-bytes-sequential-appends-and-replay
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        [first-id second-id] (:split-ids golden)]
    (doseq [split-id [first-id second-id] tag [:history :append]]
      (f/hold! ctl tag split-id))
    (try
      (let [task (f/start! ctl #(facade/record-candidate-set-review!
                               (f/deps fixture) f/scope (bulk-request)))]
        (await (release-bulk-plans! ctl))
        (t/is (= [first-id] (values-for ctl :append)))
        (f/release! ctl :append first-id)
        (await (f/await-events! ctl :append 2))
        (t/is (= [first-id] (values-for ctl :append-done)))
        (t/is (empty? (values-for ctl :project)))
        (f/release! ctl :append second-id)
        (let [result (:value (await task))]
          (t/is (= [(:bulk-input golden)] (f/operation-inputs ctl)))
          (t/is (= (mapv #(str (:bulk-group golden) ":split:" %) [first-id second-id])
                   (mapv :review/operation-id (:split-review/receipts result))))
          (t/is (= [first-id second-id] (values-for ctl :append-done)))
          (t/is (= 1 (count (values-for ctl :project))))
          (assert-projector-arguments! fixture)
          (t/is (not (contains? result :split-review/receipt))))
        (await (assert-complete-replay! fixture)))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async append-failure-preserves-earlier-history-and-skips-projection
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        failure (f/error "second append failed")
        second-id (second (:split-ids golden))]
    (f/fail! ctl :append second-id failure)
    (try
      (let [result (await (f/outcome! #(facade/record-candidate-set-review!
                                      (f/deps fixture) f/scope (bulk-request))))]
        (t/is (identical? failure (:error result)))
        (t/is (= (:split-ids golden) (values-for ctl :append)))
        (t/is (= [(first (:split-ids golden))] (values-for ctl :append-done)))
        (t/is (= [1 0] (mapv count (await (histories! fixture)))))
        (t/is (empty? (values-for ctl :project))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async projection-failure-preserves-successful-history-and-error-identity
  (let [fixture (await (f/seeded!))
        ctl (:control fixture)
        failure (f/error "projection failed")]
    (f/fail! ctl :project (:set-id golden) failure)
    (try
      (let [result (await (f/outcome! #(facade/record-candidate-set-review!
                                      (f/deps fixture) f/scope (bulk-request))))]
        (t/is (identical? failure (:error result)))
        (t/is (= [1 1] (mapv count (await (histories! fixture)))))
        (t/is (= 1 (count (values-for ctl :project))))
        (t/is (= [:append-done :project]
                 (take-last 2 (tags ctl)))))
      (finally (await (f/cleanup! ctl))))))

(defn- ^:async assert-early-public-success!
  "Early success also fails an unattained observer instead of stranding it."
  []
  (let [ctl (f/control)
        waiting (f/outcome! #(f/await-events! ctl :never 1))]
    (try
      (await (f/start! ctl (constantly :done)))
      (t/is (= :public-completed-before-events
               (:fixture/error (ex-data (:error (await waiting))))))
      (let [result (await (f/outcome! #(f/await-events! ctl :never 1)))]
        (t/is (= :public-completed-before-events
                 (:fixture/error (ex-data (:error result))))))
      (finally (await (f/cleanup! ctl))))))

(t/deftest ^:async early-public-rejection-settles-waiters-and-drains-owned-branches
  (let [fixture (await (inventory-fixture!))
        ctl (:control fixture)
        failure (f/error "early public rejection")
        deps (merge (f/deps fixture) (f/resources [:de :es :fr]) {:receipts-snapshot []})]
    (try
      (let [task (f/start! ctl #(facade/reviewable-translations! deps f/scope))]
        (await (f/await-events! ctl :dispatch 3))
        (f/release! ctl :dispatch (f/dispatch-key :de))
        (await (f/await-events! ctl :dispatch-done 1))
        (t/is (nil? @(:public-outcome ctl)))
        (let [waiting (f/outcome! #(f/await-events! ctl :history 1))]
          (f/reject! ctl :dispatch (f/dispatch-key :es) failure)
          (t/is (identical? failure (:error (await waiting)))))
        (t/is (identical? failure (:error (await task))))
        (t/is (identical? failure
                          (:error (await (f/outcome! #(f/await-events! ctl :history 1))))))
        (t/is (= {:value nil} (await (f/outcome! #(f/await-events! ctl :dispatch 3)))))
        (await (f/cleanup! ctl))
        (t/is (= #{(f/dispatch-key :de) (f/dispatch-key :fr)}
                 (set (values-for ctl :dispatch-done))))
        (await (assert-early-public-success!)))
      (finally (await (f/cleanup! ctl))))))
