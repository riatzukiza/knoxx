(ns knoxx.frontend.pages.translations.review-form-sync
  "React synchronization for the selected split's immutable review state."
  (:require [helix.hooks :as hooks]
            [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.review-draft :as draft]))

(defn use-review-form-sync!
  "Rehydrate pristine state; preserve a newer current same-authority draft."
  ([selected-segment set-form!]
   (use-review-form-sync! selected-segment set-form! nil))
  ([selected-segment set-form! draft-state]
   (hooks/use-effect
    [(:id selected-segment)
     (:candidate_set_id selected-segment)
     (:manifest_id selected-segment)
     (logic/segment-review-identity selected-segment)]
    (if draft-state
      (draft/hydrate! draft-state selected-segment set-form!)
      (set-form! (logic/segment-review-form selected-segment)))
    nil)))
