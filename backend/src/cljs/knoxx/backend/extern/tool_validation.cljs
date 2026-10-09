(ns knoxx.backend.extern.tool-validation
  "Standards validator for trusted non-Malli tool descriptors.
   GPL-3.0-or-later. No coercion, default insertion or field removal."
  (:require ["@modelcontextprotocol/sdk/validation/ajv" :refer [AjvJsonSchemaValidator]]
            ["ajv" :default Ajv]
            ["ajv-formats" :default add-formats]))

(def supported-dialects
  #{nil "http://json-schema.org/draft-07/schema#" "https://json-schema.org/draft-07/schema#"})

(defn trusted-json-validator
  "Compile the installed protocol SDK's draft-07 validator. Absent dialect uses
   that explicit draft-07 convention. Unknown dialect or compile failure leaves
   a capability uncallable; no partial schema translation is substituted."
  [schema]
  (when (and (map? schema) (= "object" (:type schema))
             (contains? supported-dialects (:$schema schema)))
    (try
      (let [ajv (Ajv. #js {:strictSchema true :strictTypes false :strictTuples false
                           :strictRequired false :validateSchema true :validateFormats true
                           :allErrors true :coerceTypes false :useDefaults false :removeAdditional false})
            _ (add-formats ajv)
            provider (AjvJsonSchemaValidator. ajv)
            validate (.getValidator provider (clj->js schema))]
        (fn [arguments]
          (true? (aget (validate (clj->js arguments)) "valid"))))
      ;; knoxx-lint/allow-silent-catch — compilation failure refuses execution.
      (catch :default _ nil))))
