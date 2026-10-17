(ns knoxx.backend.domain.app-agent-requests
  (:require [clojure.string :as str]))

(defn compact-agent-spec-overrides
  "Remove only nil, blank-string and empty sequential request overrides."
  [agent-spec]
  (into {}
        (remove (fn [[_ value]]
                  (or (nil? value)
                      (and (string? value) (str/blank? value))
                      (and (sequential? value) (empty? value)))))
        agent-spec))

(defn- requested-role
  [parsed]
  (or (get-in parsed [:agent-spec :role])
      (some-> (:auth-context parsed) :role str str/trim not-empty)
      (some->> (get-in parsed [:auth-context :roleSlugs]) seq first str str/trim not-empty)))

(defn- allow-policy?
  [policy]
  (= "allow" (some-> (:effect policy) str str/lower-case)))

(defn- tool-policy-id
  [policy]
  (some-> (or (:toolId policy) (:tool-id policy)) str str/trim not-empty))

(defn- ctx-tool-policies
  [ctx]
  (vec (or (:toolPolicies ctx)
           (:tool-policies ctx)
           [])))

(defn- parsed-auth-tool-policies
  [parsed]
  (vec (or (get-in parsed [:auth-context :toolPolicies])
           (get-in parsed [:auth-context :tool-policies])
           [])))

(defn- requested-tool-policies
  [parsed]
  (let [from-spec (vec (or (get-in parsed [:agent-spec :tool-policies]) []))
        from-auth (parsed-auth-tool-policies parsed)]
    (cond
      (seq from-spec) from-spec
      (seq from-auth) from-auth
      :else [])))

(defn- effective-tool-policies
  [ctx parsed administrator?]
  (let [requested (requested-tool-policies parsed)
        context-policies (ctx-tool-policies ctx)]
    (cond
      (and (nil? ctx) (seq requested)) requested
      (and (nil? ctx) (:auth-context parsed)) (parsed-auth-tool-policies parsed)
      (empty? requested) context-policies
      administrator? requested
      :else (let [allowed (->> context-policies
                               (filter allow-policy?)
                               (keep tool-policy-id)
                               set)]
              (->> requested
                   (filter #(contains? allowed (tool-policy-id %)))
                   vec)))))

(defn effective-auth-context
  "Project the existing request context using the observed administrator decision."
  [ctx parsed administrator?]
  (let [base (or ctx (:auth-context parsed))
        requested-actor-id (some-> (get-in parsed [:agent-spec :actor-id]) str str/trim not-empty)
        requested-role-slug (requested-role parsed)
        role-slugs (cond
                     (and (nil? base) requested-role-slug) [requested-role-slug]
                     (and requested-role-slug (or administrator?
                                                  (contains? (into #{} (or (:roleSlugs base) [])) requested-role-slug)))
                     [requested-role-slug]
                     :else (vec (or (:roleSlugs base) [])))
        tool-policies (effective-tool-policies ctx parsed administrator?)
        resource-policies (or (get-in parsed [:agent-spec :resource-policies])
                              (get-in parsed [:auth-context :resourcePolicies])
                              (:resourcePolicies base))]
    (when (or base requested-actor-id requested-role-slug (seq tool-policies) resource-policies)
      (cond-> (or base {})
        requested-actor-id (assoc :actorId requested-actor-id)
        (seq role-slugs) (assoc :roleSlugs role-slugs)
        (or (seq tool-policies) (some? base)) (assoc :toolPolicies tool-policies)
        resource-policies (assoc :resourcePolicies resource-policies)))))

(defn auth-context-with-actor
  "Apply only the existing nonblank requested actor override."
  [ctx actor-id]
  (if-let [actor-id* (some-> actor-id str str/trim not-empty)]
    (assoc (or ctx {}) :actorId actor-id*)
    ctx))


(defn queued-response
  "Project the existing queued response, retaining the chat-only camel aliases."
  [body policy-model aliases?]
  (cond-> {:ok true :queued true :run_id (:run-id body)
           :conversation_id (:conversation-id body) :session_id (:session-id body) :model policy-model}
    aliases? (assoc :runId (:run-id body) :conversationId (:conversation-id body) :sessionId (:session-id body))))
