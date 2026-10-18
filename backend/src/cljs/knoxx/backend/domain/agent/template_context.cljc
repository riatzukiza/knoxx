(ns knoxx.backend.domain.agent.template-context
  "Pure data projections for trusted contract template context values.")

(defn- key-candidates
  [k]
  (let [raw (cond
              (keyword? k) (name k)
              (symbol? k) (name k)
              (string? k) k
              :else (str k))]
    (distinct
     (remove nil?
             [k
              (when raw (keyword raw))
              (when raw raw)
              (when (and (keyword? k) (namespace k))
                (str (namespace k) "/" (name k)))]))))

(defn lookup-map
  "Read the first present key alias, preserving values and default identity."
  [m k not-found]
  (loop [candidates (seq (key-candidates k))]
    (if candidates
      (let [candidate (first candidates)]
        (if (contains? m candidate)
          (get m candidate)
          (recur (next candidates))))
      not-found)))

(defn- auth-email
  [auth-context user]
  (or (:email auth-context)
      (:user-email auth-context)
      (:userEmail auth-context)
      (:email user)
      (:user-email user)
      (:userEmail user)))

(defn- auth-display-name
  [auth-context user email]
  (or (:name auth-context)
      (:display-name auth-context)
      (:displayName auth-context)
      (:name user)
      (:display-name user)
      (:displayName user)
      email))

(defn- auth-org-slug
  [auth-context org]
  (or (:org-slug auth-context)
      (:orgSlug auth-context)
      (:slug org)
      (:org-slug org)
      (:orgSlug org)))

(defn auth-context-values
  "Project the existing authentication aliases used by legacy placeholders."
  [auth-context]
  (let [user (:user auth-context)
        org (:org auth-context)
        membership (:membership auth-context)
        email (auth-email auth-context user)
        display-name (auth-display-name auth-context user email)
        org-slug (auth-org-slug auth-context org)]
    {"name" display-name
     "email" email
     "user.email" email
     "user.name" display-name
     "org.slug" org-slug
     "membership.id" (or (:membership-id auth-context)
                          (:membershipId auth-context)
                          (:id membership))}))
