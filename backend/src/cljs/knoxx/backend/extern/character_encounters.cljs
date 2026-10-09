(ns knoxx.backend.extern.character-encounters
  "Source timestamp boundary for admitted encounter evidence. GPL-3.0-or-later.")

(def ^:private source-instant-pattern
  #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))$")

(defn- leap-year? [year]
  (and (zero? (mod year 4)) (or (not (zero? (mod year 100))) (zero? (mod year 400)))))

(defn- calendar-valid? [year month day hour minute second offset-hour offset-minute]
  (let [days [31 (if (leap-year? year) 29 28) 31 30 31 30 31 31 30 31 30 31]]
    (and (<= 1 month 12) (<= 1 day (get days (dec month) 0))
         (<= 0 hour 23) (<= 0 minute 59) (<= 0 second 59)
         (<= 0 offset-hour 23) (<= 0 offset-minute 59))))

(defn normalize-instant
  "Normalize strict, valid ISO source time to the millisecond precision of the
   existing encounter grammar. Reject calendar rollover, missing time/zone and
   leap seconds; never substitute observation time for an invalid source fact."
  [source-string]
  (let [parts (when (string? source-string) (re-matches source-instant-pattern source-string))
        [_ year month day hour minute second _fraction _zone _sign offset-hour offset-minute] parts
        numbers (mapv #(js/Number (or % "0"))
                      [year month day hour minute second offset-hour offset-minute])]
    (when-not (and parts (apply calendar-valid? numbers))
      (throw (ex-info "Encounter source timestamp is invalid" {:reason :invalid-source-instant})))
    (let [parsed (js/Date.parse source-string)
          canonical (when (js/Number.isFinite parsed) (.toISOString (js/Date. parsed)))]
      (when-not (and canonical (re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$" canonical))
        (throw (ex-info "Encounter source timestamp is invalid" {:reason :invalid-source-instant})))
      canonical)))
