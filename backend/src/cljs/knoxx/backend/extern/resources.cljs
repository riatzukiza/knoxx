(ns knoxx.backend.extern.resources
  "Native observations for the resource editor and its compatibility HTTP surface."
  (:require [cljs.reader :as reader]
            [knoxx.backend.domain.contracts.resolve :as contracts-resolve]
            [knoxx.backend.domain.resources.loader :as resources]))

(defn read-edn [text] (reader/read-string text))

(defn positive-int
  "Preserve the editor's native parseInt observation, including permissive prefixes."
  [value]
  (let [n (js/parseInt value 10)]
    (when (and (number? n) (not (js/isNaN n)) (pos? n)) n)))

(defn error-message [error] (.-message error))

(defn error-code [error] (.-code error))

(defn text-response!
  "Keep the legacy text helper's receiver, arguments and content type."
  [reply status text]
  (.end reply (.status reply status) text (clj->js {"Content-Type" "text/plain; charset=utf-8"})))

(defn body-map
  "Decode the original body fallback without adopting generic no-content policy."
  [request]
  (js->clj (or (aget request "body") (js/Object.)) :keywordize-keys true))

(defn- route-scalar
  [value]
  (if (or (nil? value) (string? value) (number? value) (boolean? value) (keyword? value))
    value
    (str value)))

(defn raw-body
  "Observe the compatibility PUT body before its original str/or coercion."
  [request]
  (route-scalar (aget request "body")))

(defn request-param
  "Observe an ID parameter before the original str/or coercion."
  [request name]
  (route-scalar (aget request "params" name)))

(defn request-query
  "Return an opaque query value; defer native coercion to its original consumer."
  [request name]
  (aget request "query" name))

(defn resource-class
  "Observe an opaque query class through the unchanged loader inside its caller's guard."
  [raw]
  (resources/resource-class raw))

(defn ui-actions-for-actor
  "Delegate opaque query observations at the original UI resolver's coercion boundary."
  [config actor-id surface]
  (contracts-resolve/ui-actions-for-actor config actor-id surface))
