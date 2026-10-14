(ns knoxx.backend.domain.action.character-intake
  "Thin registration at the existing native action seam. GPL-3.0-or-later.
   Contract resolution and observation effects are owned by infrastructure."
  (:require [knoxx.backend.domain.action.registry :as registry]
            [knoxx.backend.infra.character.intake-action :as intake]))

(registry/register-action!
 :actions/character-intake
 {:action/description "Observe currently authorized character sources without starting a creator turn or publishing."
  :action/events {:input :clock.tick :output :character.encounters.observed}}
 intake/intake-handler!)
