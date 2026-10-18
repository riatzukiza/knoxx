(ns knoxx.backend.shape.embedding
  "Lossless projections after the checked generation contracts admit their inputs.")

(defn request
  "Retain every complete input without SDK-prefix or model-only cache identity."
  [configuration inputs]
  {:model (:model configuration) :dimensions (:dimensions configuration) :input inputs})

(defn ordered-vectors
  "Restore request order only after unique complete indices have been validated."
  [response]
  (mapv :embedding (sort-by :index (:data response))))
