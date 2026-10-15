(ns knoxx.backend.law.publication-receipt-store
  "Portable contracts for scoped publication observations in canonical Clio."
  (:require [clio.law.event :as event]
            [clio.law.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]))

(def Scope
  "The exact admitted organization and server-owned project namespace."
  [:map {:closed true} [:org-id [:string {:min 1}]] [:project [:string {:min 1}]]])

(def Observation
  "A lossless receipt payload; the receipt owner validates its decoded value."
  [:map {:closed true}
   [:receipt/scope Scope]
   [:receipt/edn [:string {:min 1}]]
   [:receipt/sha256 [:string {:min 64 :max 64}]]])

(def catalog
  "Clio owns immutable envelope/schema identity and all append admission."
  {:knoxx.publication/receipt-observed
   (schema/event-schema :knoxx.publication/receipt-observed Observation)})

(defn assert-scope!
  "Refuse absent or blank trusted namespace components without normalization."
  [scope]
  (when-not (and (m/validate Scope scope)
                 (every? #(not (str/blank? %)) (vals scope)))
    (throw (ex-info "Publication receipt namespace requires organization and project"
                    {:cause :publication-receipt/invalid-scope})))
  scope)

(defn assert-directory!
  "Require an explicit nonblank storage selection; no volatile fallback."
  [directory]
  (when-not (and (string? directory) (not (str/blank? directory)))
    (throw (ex-info "Publication receipt directory is missing or blank"
                    {:cause :publication-receipt/invalid-directory})))
  directory)

(defn assert-observation!
  "Validate a stored payload before decoding or using its namespace."
  [observation]
  (when-not (and (m/validate Observation observation)
                 (re-matches #"[0-9a-f]{64}" (:receipt/sha256 observation)))
    (throw (ex-info "Invalid persisted publication receipt observation"
                    {:cause :publication-receipt/invalid-observation})))
  (assert-scope! (:receipt/scope observation))
  observation)


(def PendingAttempt
  "An exact immutable emission envelope retained with its trusted scope."
  [:map {:closed true}
   [:scope Scope]
   [:event (get catalog :knoxx.publication/receipt-observed)]])

(def FailedAttempt
  "Internal failed-commit capability; never a ledger payload or HTTP body."
  [:map {:closed true}
   [:cause [:= :publication-receipt/commit-unacknowledged]]
   [:publication-receipt/pending PendingAttempt]
   [:publication-receipt/retry! [:fn fn?]]])

(defn assert-pending-attempt!
  "Require the unchanged canonical event and its matching admitted scope."
  [{:keys [scope event] :as pending}]
  (when-not (and (m/validate PendingAttempt pending)
                 (event/event-identity-valid? event)
                 (= scope (get-in event [:event/data :receipt/scope])))
    (throw (ex-info "Invalid pending publication receipt attempt"
                    {:cause :publication-receipt/invalid-pending-attempt})))
  (assert-scope! scope)
  (assert-observation! (:event/data event))
  pending)

(defn assert-failed-attempt!
  "Validate an explicit retry capability while retaining the exact attempt."
  [failure]
  (when-not (m/validate FailedAttempt failure)
    (throw (ex-info "Invalid failed publication receipt attempt"
                    {:cause :publication-receipt/invalid-failed-attempt})))
  (assert-pending-attempt! (:publication-receipt/pending failure))
  failure)
