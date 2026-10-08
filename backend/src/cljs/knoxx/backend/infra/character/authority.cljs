(ns knoxx.backend.infra.character.authority
  "Current actor scope over the canonical stored policy resolver.
   GPL-3.0-or-later. Injectable resolver is a trusted host/test port."
  (:require [knoxx.backend.infra.db.policy :as policy]
            [knoxx.backend.infra.auth.authz :as authz]))

(defn ^:async resolve-current!
  [config auth-context agent-spec]
  (let [actor-id (or (:actor-id agent-spec) (:actorId auth-context)
                     (get-in auth-context [:actor :binding]))
        scope {:actor-id actor-id
               :org-id (authz/ctx-org-id auth-context)
               :membership-id (authz/ctx-membership-id auth-context)}
        resolver (or (:resolve-agent-authority! config) policy/resolve-agent-actor-context!)
        context (await (resolver scope))]
    (when-not (and context (= actor-id (or (get-in context [:actor :binding]) (:actorId context)))
                   (get-in context [:org :id]) (get-in context [:membership :id])
                   (or (nil? (:org-id scope)) (= (:org-id scope) (get-in context [:org :id])))
                   (or (nil? (:membership-id scope)) (= (:membership-id scope) (get-in context [:membership :id]))))
      (throw (ex-info "Actor authority was not resolved to a stored scope" {:reason :invalid-actor-context})))
    ;; Existing caller-specific resource ceilings narrow stored grants. The
    ;; canonical tooling policy combiner still handles role/contract limits.
    (cond-> (assoc context :actorId actor-id)
      (:resourcePolicies auth-context) (assoc :resourcePolicies (:resourcePolicies auth-context))
      (and (nil? (:resourcePolicies auth-context)) (:resource-policies agent-spec))
      (assoc :resourcePolicies (:resource-policies agent-spec)))))
