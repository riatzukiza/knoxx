(ns knoxx.backend.law.translation-review-verification-resource
  "Portable construction of the existing resource verification fixture.

   Hashing, source observation and clocks are supplied by the owning extern.
   All translation identity and admission semantics remain in existing laws."
  (:require [knoxx.backend.law.translation-dispatch :as dispatch-law]
            [knoxx.backend.law.translation-source-split :as source-split]
            [knoxx.backend.law.translation-split :as split]))

(defn keyword-wire
  "Encode a qualified resource identity using the existing wire spelling."
  [value]
  (str (namespace value) "/" (name value)))

(defn dispatch-key
  "Delegate fixture dispatch identity to the production dispatch law."
  [opts source-revision]
  (dispatch-law/dispatch-key
   {:org-id (:org-id opts)
    :project (:project opts)
    :garden (keyword-wire (:garden opts))
    :document (:document opts)
    :source-locale :en
    :locale (:target-locale opts)
    :revision source-revision}))

(defn- dispatch-context
  [opts source-revision]
  (cond-> {:dispatch/garden (keyword-wire (:garden opts))
           :dispatch/document-wire-id (keyword-wire (:document opts))
           :dispatch/source-locale :en
           :dispatch/source-digest source-revision
           :dispatch/org-id (:org-id opts)
           :dispatch/membership-id (str "verification-" (:run-id opts))}
    (some? (:project opts))
    (assoc :dispatch/project (:project opts))))

(defn accepted-dispatch
  "Construct the original production-law fixture admission."
  [opts source-revision admitted-at]
  (let [record
        (dispatch-law/dispatch-record
         {:document (:document opts)
          :locale (:target-locale opts)
          :revision source-revision
          :replace-stale? false}
         (dispatch-context opts source-revision)
         :dispatch/accepted
         admitted-at
         :attempt-id
         (str "verify-translation-split-review-attempt-" (:run-id opts)))]
    (when-not (= (dispatch-key opts source-revision) (:dispatch/key record))
      (throw (ex-info "verification dispatch does not match resource work" {})))
    record))

(defn- translated-part
  [index]
  (str "Traducci\u00f3n candidata " (inc index)
       " para verificar la revisi\u00f3n durable."
       (if (< index 2) "\n\n" "\n")))

(defn- manifest-for
  [hash-fn opts source-text parts source-revision]
  (split/split-manifest
   hash-fn
   {:org-id (:org-id opts)
    :project (:project opts)
    :garden (:garden opts)
    :document (:document opts)
    :source-locale :en
    :target-locale (:target-locale opts)
    :source-revision source-revision
    :source-text source-text
    :source-parts parts}))

(defn- candidate-evidence
  [hash-fn manifest output-revision]
  (let [claim (split/candidate-claim hash-fn manifest output-revision)
        candidates
        (mapv (fn [index member]
                (split/candidate-split hash-fn member
                                       (translated-part index)))
              (range)
              (:candidate-claim/members claim))]
    {:claim claim
     :candidates candidates
     :candidate-set
     (split/complete-candidate-set hash-fn manifest claim candidates)}))

(defn- turn-for
  [hash-fn manifest claim dispatch]
  (let [execution
        (split/execution-snapshot
         hash-fn
         {:agent-id "publication_translator"
          :model "verification-fixture"
          :thinking :medium
          :system-prompt "Verification-only production-shaped candidate."
          :tool-ids ["save_translation"]})]
    (split/translation-turn-admission
     hash-fn
     {:dispatch-key (:dispatch/key dispatch)
      :run-id (:dispatch/batch-id dispatch)
      :admitted-at (:dispatch/at dispatch)
      :manifest manifest
      :candidate-claim claim
      :execution execution
      :memory (split/memory-snapshot {:status :empty :examples []})})))

(defn candidate-context
  "Construct the original three-part fixture using supplied observations."
  [hash-fn opts source-text dispatch observed-source-revision]
  (let [parts (source-split/source-parts source-text)
        source-revision observed-source-revision]
    (when-not (= 3 (count parts))
      (throw (ex-info "the verification source must split into exactly three parts"
                      {:parts (count parts)})))
    (when-not (= source-revision (:dispatch/revision dispatch))
      (throw (ex-info "verification dispatch does not bind the source bytes" {})))
    (let [output-revision (dispatch-law/output-revision dispatch)
          manifest (manifest-for hash-fn opts source-text parts source-revision)
          {:keys [claim candidates candidate-set]}
          (candidate-evidence hash-fn manifest output-revision)]
      {:source-revision source-revision
       :output-revision output-revision
       :manifest manifest
       :claim claim
       :candidates candidates
       :candidate-set candidate-set
       :dispatch dispatch
       :turn (turn-for hash-fn manifest claim dispatch)})))

(defn- split-lineage
  [candidate-set turn]
  {:translation/split-manifest-id (:candidate-set/manifest-id candidate-set)
   :translation/candidate-claim-id (:candidate-set/claim-id candidate-set)
   :translation/candidate-set-id (:candidate-set/id candidate-set)
   :translation/candidate-set-digest (:candidate-set/digest candidate-set)
   :translation/split-count (count (:candidate-set/members candidate-set))
   :translation/split-turn-admitted-at (:translation-turn/admitted-at turn)})

(defn completion-receipt
  "Construct the original receipt from supplied clock and digest observations."
  [dispatch candidate-set turn admitted-at content-digest]
  (dispatch-law/translation-receipt
   dispatch
   (dispatch-law/output-revision dispatch)
   admitted-at
   content-digest
   (split-lineage candidate-set turn)))
