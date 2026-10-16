(ns knoxx.backend.infra.routes.resources.registration
  "Original fifteen route registrations and permission/context sequencing."
  (:require [clojure.string :as str]
            [knoxx.backend.extern.resources :as native]
            [knoxx.backend.infra.publication-admission-hook :as publication-admission-hook]
            [knoxx.backend.infra.resources.validation :as validation]
            [knoxx.backend.infra.routes.resources.handlers :as handlers]))

(defn- request-resource-kind
  [request default]
  (or (native/request-query request "kind")
      (native/request-query request "class")
      default))

(defn- request-contract-class
  "Compatibility alias for old contract route clients."
  [request default]
  (request-resource-kind request default))

(defn- body-resource-kind
  ([body default]
   (body-resource-kind body nil default))
  ([body request default]
   (or (:kind body)
       (:class body)
       (:resource_kind body)
       (:resource-kind body)
       (:resourceClass body)
       (:resource-class body)
       (:contract_class body)
       (:contract-class body)
       (some-> request (native/request-query "kind"))
       (some-> request (native/request-query "class"))
       default)))

(defn- body-contract-class
  "Compatibility alias for old contract route clients."
  ([body default]
   (body-resource-kind body default))
  ([body request default]
   (body-resource-kind body request default)))

(defn- body-edn-text
  [body]
  (str (or (:ednText body)
           (:edn_text body)
           (:edn-text body)
           "")))

(defn- with-route-context
  [runtime do-ctx do-err f]
  (fn [request reply]
    (do-ctx runtime request reply
      (fn [ctx]
        (try
          (f ctx request reply)
          (catch :default err
            (do-err reply err)))))))

(defn- agent-ui-actions-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [actor-id (or (native/request-query request "actor")
                         (native/request-query request "actor_id")
                         (native/request-query request "actorId"))
            surface (or (native/request-query request "surface")
                        (native/request-query request "surface_id")
                        (native/request-query request "surfaceId"))]
        (handlers/handle-ui-actions (partial do-json reply) config actor-id surface)))))

(defn- agent-list-contracts-route
  [runtime config do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [safe-kind (validation/safe-contract-class (request-contract-class request "agents"))]
        (if-not (:ok safe-kind)
          (native/text-response! reply 400 (str ";; Invalid contract class: " (:error safe-kind)))
          (handlers/handle-agent-list-contracts (partial native/text-response! reply) config (:class safe-kind)))))))

(defn- agent-validate-contract-route
  [runtime do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [body (native/body-map request)
            safe-kind (validation/safe-contract-class (body-contract-class body request "agents"))]
        (if-not (:ok safe-kind)
          (do-json reply 400 {:detail "Invalid contract class" :error (:error safe-kind)})
          (handlers/handle-agent-validate-contract-edn (partial do-json reply) (:class safe-kind) (body-edn-text body)))))))

(defn- agent-get-contract-route
  [runtime config do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [contract-id (str (or (native/request-param request "contractId") ""))
            safe (validation/safe-contract-id contract-id)
            safe-kind (validation/safe-contract-class (request-contract-class request "agents"))]
        (cond
          (str/blank? contract-id) (native/text-response! reply 400 ";; contractId is required")
          (not (:ok safe-kind)) (native/text-response! reply 400 (str ";; Invalid contract class: " (:error safe-kind)))
          (not (:ok safe)) (native/text-response! reply 400 (str ";; Invalid contractId: " (:error safe)))
          :else (handlers/handle-agent-get-contract-edn (partial native/text-response! reply) config (:class safe-kind) (:id safe)))))))

(defn- agent-put-contract-route
  [runtime config do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [contract-id (str (or (native/request-param request "contractId") ""))
            safe (validation/safe-contract-id contract-id)
            safe-kind (validation/safe-contract-class (request-contract-class request "agents"))
            edn-text (str (or (native/raw-body request) ""))]
        (cond
          (str/blank? contract-id) (native/text-response! reply 400 ";; contractId is required")
          (not (:ok safe-kind)) (native/text-response! reply 400 (str ";; Invalid contract class: " (:error safe-kind)))
          (not (:ok safe)) (native/text-response! reply 400 (str ";; Invalid contractId: " (:error safe)))
          :else (handlers/handle-agent-put-contract-edn (partial native/text-response! reply) config
                                               (:class safe-kind) (:id safe) edn-text
                                               ctx publication-admission-hook/admit!))))))

(defn- register-agent-contract-routes!
  [app runtime config helpers]
  (let [do-route (:route! helpers)
        do-json (:json-response! helpers)
        do-err (:error-response! helpers)
        do-ctx (:with-request-context! helpers)
        do-perm (:ensure-permission! helpers)]
    (do-route app "GET" "/api/contracts/ui-actions"
              (agent-ui-actions-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "GET" "/api/agent/contracts"
              (agent-list-contracts-route runtime config do-err do-ctx do-perm))
    (do-route app "POST" "/api/agent/contracts/validate"
              (agent-validate-contract-route runtime do-json do-err do-ctx do-perm))
    (do-route app "GET" "/api/agent/contracts/:contractId"
              (agent-get-contract-route runtime config do-err do-ctx do-perm))
    (do-route app "PUT" "/api/agent/contracts/:contractId"
              (agent-put-contract-route runtime config do-err do-ctx do-perm))))

(defn- admin-list-resources-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [kind (request-resource-kind request nil)
            safe-kind (if kind (validation/safe-resource-class kind) {:ok true :class nil})]
        (if-not (:ok safe-kind)
          (do-json reply 400 {:detail "Invalid resource kind" :error (:error safe-kind)})
          (handlers/handle-list-resources (partial do-json reply) config (:class safe-kind)))))))

(defn- admin-get-resource-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [resource-id (str (or (native/request-param request "resourceId") ""))
            safe (validation/safe-resource-id resource-id)
            safe-kind (validation/safe-resource-class (request-resource-kind request "agents"))]
        (cond
          (str/blank? resource-id) (do-json reply 400 {:detail "resourceId is required"})
          (not (:ok safe-kind)) (do-json reply 400 {:detail "Invalid resource kind" :error (:error safe-kind)})
          (not (:ok safe)) (do-json reply 400 {:detail "Invalid resourceId" :error (:error safe)})
          :else (handlers/handle-get-resource (partial do-json reply) config (:class safe-kind) (:id safe)))))))

(defn- admin-save-resource-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (do-perm ctx "platform.org.create")
      (let [resource-id (str (or (native/request-param request "resourceId") ""))
            body (native/body-map request)
            safe (validation/safe-resource-id resource-id)
            safe-kind (validation/safe-resource-class (body-resource-kind body request "agents"))]
        (cond
          (str/blank? resource-id) (do-json reply 400 {:detail "resourceId is required"})
          (not (:ok safe-kind)) (do-json reply 400 {:detail "Invalid resource kind" :error (:error safe-kind)})
          (not (:ok safe)) (do-json reply 400 {:detail "Invalid resourceId" :error (:error safe)})
          :else (handlers/handle-save-resource (partial do-json reply) config (:class safe-kind) (:id safe)
                                      (body-edn-text body) ctx publication-admission-hook/admit!))))))

(defn- admin-validate-resource-route
  [runtime do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (do-perm ctx "platform.org.create")
      (let [body (native/body-map request)
            safe-kind (validation/safe-resource-class (body-resource-kind body "agents"))]
        (if-not (:ok safe-kind)
          (do-json reply 400 {:detail "Invalid resource kind" :error (:error safe-kind)})
          (handlers/handle-validate-resource (partial do-json reply) (:class safe-kind) (body-edn-text body)))))))

(defn- admin-copy-resource-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (do-perm ctx "platform.org.create")
      (let [source-id (str (or (native/request-param request "resourceId") ""))
            body (native/body-map request)
            new-id (str (or (:newId body) ""))
            safe-kind (validation/safe-resource-class (body-resource-kind body "agents"))
            safe-source (validation/safe-resource-id source-id)
            safe-new (validation/safe-resource-id new-id)]
        (cond
          (not (:ok safe-kind)) (do-json reply 400 {:detail "Invalid resource kind" :error (:error safe-kind)})
          (or (str/blank? source-id) (str/blank? new-id)) (do-json reply 400 {:detail "source resourceId and newId are required"})
          (not (:ok safe-source)) (do-json reply 400 {:detail "Invalid source resourceId" :error (:error safe-source)})
          (not (:ok safe-new)) (do-json reply 400 {:detail "Invalid newId" :error (:error safe-new)})
          :else (handlers/handle-copy-resource (partial do-json reply) config (:class safe-kind)
                                      (:id safe-source) (:id safe-new)
                                      ctx publication-admission-hook/admit!))))))

(defn- admin-list-contracts-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [kind (request-contract-class request nil)
            safe-kind (if kind (validation/safe-contract-class kind) {:ok true :class nil})]
        (if-not (:ok safe-kind)
          (do-json reply 400 {:detail "Invalid contract class" :error (:error safe-kind)})
          (handlers/handle-list-contracts (partial do-json reply) config (:class safe-kind)))))))

(defn- admin-get-contract-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (when ctx (do-perm ctx "agent.chat.use"))
      (let [contract-id (str (or (native/request-param request "contractId") ""))
            safe (validation/safe-contract-id contract-id)
            safe-kind (validation/safe-contract-class (request-contract-class request "agents"))]
        (cond
          (str/blank? contract-id) (do-json reply 400 {:detail "contractId is required"})
          (not (:ok safe-kind)) (do-json reply 400 {:detail "Invalid contract class" :error (:error safe-kind)})
          (not (:ok safe)) (do-json reply 400 {:detail "Invalid contractId" :error (:error safe)})
          :else (handlers/handle-get-contract (partial do-json reply) config (:class safe-kind) (:id safe)))))))

(defn- admin-save-contract-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (do-perm ctx "platform.org.create")
      (let [contract-id (str (or (native/request-param request "contractId") ""))
            body (native/body-map request)
            safe (validation/safe-contract-id contract-id)
            safe-kind (validation/safe-contract-class (body-contract-class body request "agents"))]
        (cond
          (str/blank? contract-id) (do-json reply 400 {:detail "contractId is required"})
          (not (:ok safe-kind)) (do-json reply 400 {:detail "Invalid contract class" :error (:error safe-kind)})
          (not (:ok safe)) (do-json reply 400 {:detail "Invalid contractId" :error (:error safe)})
          :else (handlers/handle-save-contract (partial do-json reply) config (:class safe-kind) (:id safe)
                                      (body-edn-text body) ctx publication-admission-hook/admit!))))))

(defn- admin-validate-contract-route
  [runtime do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (do-perm ctx "platform.org.create")
      (let [body (native/body-map request)
            safe-kind (validation/safe-contract-class (body-contract-class body "agents"))]
        (if-not (:ok safe-kind)
          (do-json reply 400 {:detail "Invalid contract class" :error (:error safe-kind)})
          (handlers/handle-validate-contract (partial do-json reply) (:class safe-kind) (body-edn-text body)))))))

(defn- admin-copy-contract-route
  [runtime config do-json do-err do-ctx do-perm]
  (with-route-context runtime do-ctx do-err
    (fn [ctx request reply]
      (do-perm ctx "platform.org.create")
      (let [source-id (str (or (native/request-param request "contractId") ""))
            body (native/body-map request)
            new-id (str (or (:newId body) ""))
            safe-kind (validation/safe-contract-class (body-contract-class body "agents"))
            safe-source (validation/safe-contract-id source-id)
            safe-new (validation/safe-contract-id new-id)]
        (cond
          (not (:ok safe-kind)) (do-json reply 400 {:detail "Invalid contract class" :error (:error safe-kind)})
          (or (str/blank? source-id) (str/blank? new-id)) (do-json reply 400 {:detail "source contractId and newId are required"})
          (not (:ok safe-source)) (do-json reply 400 {:detail "Invalid source contractId" :error (:error safe-source)})
          (not (:ok safe-new)) (do-json reply 400 {:detail "Invalid newId" :error (:error safe-new)})
          :else (handlers/handle-copy-contract (partial do-json reply) config (:class safe-kind)
                                      (:id safe-source) (:id safe-new)
                                      ctx publication-admission-hook/admit!))))))

(defn- register-admin-resource-routes!
  [app runtime config helpers]
  (let [do-route (:route! helpers)
        do-json (:json-response! helpers)
        do-err (:error-response! helpers)
        do-ctx (:with-request-context! helpers)
        do-perm (:ensure-permission! helpers)]
    (do-route app "GET" "/api/admin/resources"
              (admin-list-resources-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "GET" "/api/admin/resources/:resourceId"
              (admin-get-resource-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "PUT" "/api/admin/resources/:resourceId"
              (admin-save-resource-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "POST" "/api/admin/resources/validate"
              (admin-validate-resource-route runtime do-json do-err do-ctx do-perm))
    (do-route app "POST" "/api/admin/resources/:resourceId/copy"
              (admin-copy-resource-route runtime config do-json do-err do-ctx do-perm))))

(defn- register-admin-contract-routes!
  [app runtime config helpers]
  (let [do-route (:route! helpers)
        do-json (:json-response! helpers)
        do-err (:error-response! helpers)
        do-ctx (:with-request-context! helpers)
        do-perm (:ensure-permission! helpers)]
    (do-route app "GET" "/api/admin/contracts"
              (admin-list-contracts-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "GET" "/api/admin/contracts/:contractId"
              (admin-get-contract-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "PUT" "/api/admin/contracts/:contractId"
              (admin-save-contract-route runtime config do-json do-err do-ctx do-perm))
    (do-route app "POST" "/api/admin/contracts/validate"
              (admin-validate-contract-route runtime do-json do-err do-ctx do-perm))
    (do-route app "POST" "/api/admin/contracts/:contractId/copy"
              (admin-copy-contract-route runtime config do-json do-err do-ctx do-perm))))

(defn register-resource-routes!
  [app runtime config helpers]
  (register-agent-contract-routes! app runtime config helpers)
  (register-admin-resource-routes! app runtime config helpers)
  (register-admin-contract-routes! app runtime config helpers)
  nil)

(defn register-contracts-routes!
  "Compatibility alias for old route registration."
  [app runtime config helpers]
  (register-resource-routes! app runtime config helpers))
