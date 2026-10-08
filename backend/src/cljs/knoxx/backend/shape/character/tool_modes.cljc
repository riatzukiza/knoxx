(ns knoxx.backend.shape.character.tool-modes
  "Engine-independent capability mode data. License: GPL-3.0-or-later.")

(def ToolDescriptor
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:name [:string {:min 1}]]
   [:description {:optional true} :string]])

(def Mode
  [:map {:closed true}
   [:description :string]
   [:tools [:vector [:string {:min 1}]]]])

(def ModeConfiguration
  [:map {:closed true}
   [:initial [:string {:min 1}]]
   [:core [:vector [:string {:min 1}]]]
   [:modes [:map-of [:string {:min 1}] Mode]]])

(def CharacterContext
  [:map {:closed true}
   [:identity [:string {:min 1}]]
   [:persona :string]
   [:snapshot [:map {:closed false}]]
   [:evidence-ids [:vector [:string {:min 1}]]]
   [:revision {:optional true} [:string {:min 1}]]])
