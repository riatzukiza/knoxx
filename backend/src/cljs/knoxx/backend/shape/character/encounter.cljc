(ns knoxx.backend.shape.character.encounter
  "Portable encounter data and explicit OpenPlanner wire morphisms."
  (:require [clojure.string :as str]))

(def NonBlankString
  "A bounded identifier; source adapters must preserve external identifiers."
  [:and [:string {:min 1 :max 512}] [:fn #(not (str/blank? %))]])

(def Instant
  "Canonical UTC timestamp spelling used for deterministic ordering."
  [:re #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$"])

(def Owner
  "The tenant, project and character authorized to retain an encounter."
  [:map {:closed true}
   [:org-id NonBlankString]
   [:project NonBlankString]
   [:character-id NonBlankString]])

(def Source
  "An exact source account and resource; public visibility grants no authority."
  [:map {:closed true}
   [:kind NonBlankString]
   [:account-id NonBlankString]
   [:scope-id NonBlankString]
   [:visibility [:enum :public :private]]])

(def Reactions
  "Bounded source-reported labels and counts; these are evidence, not interpreted affect."
  [:vector {:max 12}
   [:map {:closed true}
    [:label [:string {:min 1 :max 128}]]
    [:reference {:optional true} NonBlankString]
    [:count [:int {:min 0 :max 1000000000}]]]])

(def Media
  "Bounded media/emote references without claiming perceptual understanding."
  [:vector {:max 6}
   [:map {:closed true}
    [:kind [:enum :emote :image :video :audio :link :file]]
    [:reference NonBlankString]
    [:label {:optional true} [:string {:min 1 :max 128}]]]])

(def Item
  "Source-normalized content, with explicit self-output provenance."
  [:map {:closed true}
   [:external-id NonBlankString]
   [:author-id NonBlankString]
   [:occurred-at Instant]
   [:text [:string {:max 4000}]]
   [:self-output? :boolean]
   [:reactions {:optional true} Reactions]
   [:media {:optional true} Media]])

(def Coverage
  "Inspectible bounded-poll evidence; these source adapters never claim complete history."
  [:map {:closed true}
   [:mode [:enum :newest :after]]
   [:complete? [:= false]]
   [:reason [:enum :bounded-poll :overflow :provider-pagination :unknown]]
   [:fetched-count [:int {:min 0 :max 24}]]
   [:admitted-count [:int {:min 0 :max 24}]]
   [:overflow? :boolean]
   [:provider-cursor [:maybe NonBlankString]]
   [:newest-id [:maybe NonBlankString]]
   [:oldest-id [:maybe NonBlankString]]])

(def Page
  "One complete bounded source page; cursors remain opaque adapter values."
  [:map {:closed true}
   [:source Source]
   [:items [:vector {:max 24} Item]]
   [:cursor-before [:maybe NonBlankString]]
   [:cursor-after [:maybe NonBlankString]]
   [:observed-at Instant]
   [:coverage {:optional true} Coverage]])

(def Authorization
  "Evidence returned by a trusted existing authorization adapter, never a tool argument."
  [:map {:closed true}
   [:allowed? [:= true]]
   [:owner Owner]
   [:source Source]
   [:principal-id NonBlankString]
   [:evidence-id NonBlankString]
   [:checked-at Instant]])

(def Record
  "An authorized external observation or distinctly marked self-output."
  [:map {:closed true}
   [:id NonBlankString]
   [:source-id NonBlankString]
   [:owner Owner]
   [:source Source]
   [:external-id NonBlankString]
   [:author-id NonBlankString]
   [:occurred-at Instant]
   [:admitted-at Instant]
   [:content-hash NonBlankString]
   [:text [:string {:max 4000}]]
   [:self-output? :boolean]
   [:reactions Reactions]
   [:media Media]
   [:authorization Authorization]])

(def Checkpoint
  "Cursor progress is a durable event after every page member is confirmed."
  [:map {:closed true}
   [:id NonBlankString]
   [:page-id NonBlankString]
   [:stream-id NonBlankString]
   [:owner Owner]
   [:source Source]
   [:sequence [:int {:min 1}]]
   [:previous-id [:maybe NonBlankString]]
   [:cursor-before [:maybe NonBlankString]]
   [:cursor-after [:maybe NonBlankString]]
   [:event-ids [:vector {:max 24} NonBlankString]]
   [:at Instant]
   [:coverage {:optional true} Coverage]])

(defn owner-binding
  "Produce an ordered ownership tuple without map-printing instability."
  [owner]
  (mapv owner [:org-id :project :character-id]))

(defn source-binding
  "Produce an ordered source tuple that includes privacy scope."
  [source]
  (mapv source [:kind :account-id :scope-id :visibility]))

(defn coverage-binding
  "Keep poll identity deterministic without relying on map printing order."
  [coverage]
  (when coverage
    (mapv coverage [:mode :complete? :reason :fetched-count :admitted-count
                    :overflow? :provider-cursor :newest-id :oldest-id])))

(defn record-binding
  "Select immutable encounter facts; renewed authority and observation time do not mint duplicates."
  [record]
  [(owner-binding (:owner record))
   (source-binding (:source record))
   (:external-id record) (:author-id record) (:occurred-at record)
   (:content-hash record) (:self-output? record)
   (mapv #(mapv % [:label :reference :count]) (:reactions record))
   (mapv #(mapv % [:kind :reference :label]) (:media record))])

(defn record->wire
  "Encode keyword-valued visibility in the persisted event payload explicitly."
  [record]
  (-> record
      (update-in [:source :visibility] name)
      (update-in [:authorization :source :visibility] name)
      (update :media #(mapv (fn [reference] (update reference :kind name)) %))))

(defn wire->record
  "Decode only the known visibility vocabulary; validation remains the caller's boundary."
  [record]
  (let [visibility {"public" :public "private" :private
                    :public :public :private :private}
        media-kind {"emote" :emote "image" :image "video" :video
                    "audio" :audio "link" :link "file" :file
                    :emote :emote :image :image :video :video
                    :audio :audio :link :link :file :file}]
    (-> record
        (update-in [:source :visibility] visibility)
        (update-in [:authorization :source :visibility] visibility)
        (update :media #(mapv (fn [reference] (update reference :kind media-kind)) %)))))

(defn checkpoint->wire
  "Encode the checkpoint's source visibility for OpenPlanner persistence."
  [checkpoint]
  (cond-> (update-in checkpoint [:source :visibility] name)
    (:coverage checkpoint) (update :coverage #(-> % (update :mode name) (update :reason name)))))

(defn wire->checkpoint
  "Decode the checkpoint's known source visibility without accepting new values."
  [checkpoint]
  (cond-> (update-in checkpoint [:source :visibility]
                    {"public" :public "private" :private
                     :public :public :private :private})
    (:coverage checkpoint)
    (update :coverage #(-> %
                          (update :mode {"newest" :newest "after" :after :newest :newest :after :after})
                          (update :reason {"bounded-poll" :bounded-poll "overflow" :overflow
                                           "provider-pagination" :provider-pagination "unknown" :unknown
                                           :bounded-poll :bounded-poll :overflow :overflow
                                           :provider-pagination :provider-pagination :unknown :unknown})))))

(defn event-extra
  "Build the shared durable query dimensions for encounter and cursor events."
  [owner source stream-id]
  {:org_id (:org-id owner)
   :project (:project owner)
   :character_id (:character-id owner)
   :encounter_stream_id stream-id
   :source_kind (:kind source)
   :source_account_id (:account-id source)
   :source_scope_id (:scope-id source)
   :visibility (name (:visibility source))})
