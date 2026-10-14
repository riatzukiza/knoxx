(ns knoxx.backend.character.encounter-timestamp-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.character-encounters :as boundary]))

(deftest source-time-normalizes-zone-and-existing-millisecond-precision
  (is (= "2026-10-07T12:34:56.000Z" (boundary/normalize-instant "2026-10-07T12:34:56Z")))
  (is (= "2026-10-07T12:34:56.120Z" (boundary/normalize-instant "2026-10-07T14:34:56.12+02:00")))
  (is (= "2026-10-07T12:34:56.123Z" (boundary/normalize-instant "2026-10-07T12:34:56.123456Z")))
  (is (= "2024-02-29T00:00:00.000Z" (boundary/normalize-instant "2024-02-29T00:00:00Z"))))

(deftest invalid-or-missing-source-facts-never-become-observation-time
  (doseq [input [nil 7 "" "2026-10-07" "2026-10-07T12:34:56" "2026-02-29T00:00:00Z"
                 "2026-02-31T00:00:00Z" "2026-13-01T00:00:00Z" "2026-10-07T24:00:00Z"
                 "2026-10-07T12:34:60Z" "2026-10-07T12:34:56+24:00"]]
    (try
      (boundary/normalize-instant input)
      (is false "Invalid source timestamp was accepted")
      (catch :default error (is (= :invalid-source-instant (:reason (ex-data error))))))))
