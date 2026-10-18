(ns knoxx.backend.infra.memory-sessions.pages
  "Authorized session-page and search-hit retrieval; no HTTP registration."
  (:require [clojure.string :as str]
            [knoxx.backend.extern.memory-sessions :as runtime]
            [knoxx.backend.infra.auth.authz :refer [system-admin?]]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.core-memory :as core-memory
             :refer [session-matches-contract-filter? session-summary-scope-from-rows]]
            [knoxx.backend.infra.memory-sessions.cache :as cache]
            [knoxx.backend.shape.memory-sessions :as memory-shape]))

(defn interactive-session-id?
  [session-id]
  (not (str/starts-with? (str session-id) "translation-")))

(defn- fetch-session-filter-rows!
  [fetch-openplanner-session-rows! config session-id]
  (if (identical? fetch-openplanner-session-rows! core-memory/fetch-openplanner-session-rows!)
    (core-memory/fetch-openplanner-session-visibility-rows! config session-id)
    (fetch-openplanner-session-rows! config session-id)))

(defn ^:async filter-page-actor-row!
  [config fetch-openplanner-session-rows! session-matches-page-actor-filter?
   actor-id exclude-actor-ids contract-id row]
  (try
    (let [rows (await (fetch-session-filter-rows! fetch-openplanner-session-rows! config (:session row)))]
      {:row (merge row (session-summary-scope-from-rows rows))
       :visible (and (session-matches-page-actor-filter? config rows actor-id exclude-actor-ids)
                     (session-matches-contract-filter? config rows contract-id))})
    (catch :default error
      (runtime/degraded! "memory-sessions/page-visibility"
                                :excluded error)
      {:row row
       :visible false})))

(defn ^:async filter-page-actor-rows!
  [config fetch-openplanner-session-rows! session-matches-page-actor-filter? actor-id exclude-actor-ids contract-id page-rows]
  (if (and (str/blank? (str (or actor-id "")))
           (empty? exclude-actor-ids)
           (str/blank? (str (or contract-id ""))))
    (vec page-rows)
    (let [results (await (runtime/all-results!
                          (mapv (partial filter-page-actor-row!
                                         config fetch-openplanner-session-rows!
                                         session-matches-page-actor-filter?
                                         actor-id exclude-actor-ids contract-id)
                                page-rows)))]
      (->> results
           (filter :visible)
           (map :row)
           vec))))

(defn- grouped-session-rows
  [rows]
  (reduce (fn [state row]
            (let [session-id (str (:session row))]
              (if (or (str/blank? session-id)
                      (not (interactive-session-id? session-id)))
                state
                (-> state
                    (update :groups update session-id (fnil conj []) row)
                    (update :order (fn [order]
                                     (if (contains? (:seen state) session-id)
                                       order
                                       (conj order session-id))))
                    (update :seen conj session-id)))))
          {:groups {} :order [] :seen #{}}
          rows))

(defn- session-summary-title
  [session-id scope]
  (let [event-type (:event_type scope)
        trigger-id (:trigger_id scope)]
    (cond
      (and event-type trigger-id) (str "event: " event-type " trigger: " trigger-id)
      trigger-id (str "trigger: " trigger-id)
      event-type (str "event: " event-type)
      :else session-id)))

(defn- mongo-session-summary
  [session-id rows]
  (let [latest (first rows)
        scope (session-summary-scope-from-rows rows)]
    (merge {:session session-id
            :project (:project latest)
            :last_ts (:ts latest)
            :event_count (count rows)
            :title (session-summary-title session-id scope)}
           scope)))

(defn ^:async fetch-contract-session-pages-from-mongo!
  [config contract-id needed-count]
  (let [target (some-> contract-id str str/trim not-empty)
        query-limit 500]
    (when target
      (let [body (await (openplanner-client/mongo-query! (or (:openplanner-client config)
                                                             (openplanner-client/client config))
                                                         {:collection "events"
                                                          :filter {"project" (:session-project-name config)
                                                                   "session" {"$type" "string" "$ne" ""}
                                                                   "extra.contract_id" target}
                                                          :projection {"_id" 0
                                                                       "project" 1
                                                                       "session" 1
                                                                       "ts" 1
                                                                       "extra" 1}
                                                          :sort {"ts" -1}
                                                          :limit query-limit}))
            rows (vec (or (:rows body) []))
            {:keys [groups order]} (grouped-session-rows rows)
            summaries (mapv #(mongo-session-summary % (get groups %)) order)
            wanted-count (max 1 (or needed-count 1))
            selected (vec (take wanted-count summaries))
            total-events (or (:total body) 0)
            fetched-events (count rows)
            more-events? (> total-events fetched-events)
            more-sessions? (> (count summaries) wanted-count)]
        {:rows selected
         :has_more (or more-sessions? more-events?)}))))

(defn ^:async fetch-authorized-session-pages!
  [config ctx actor-id exclude-actor-ids contract-id authorized-session-ids! fetch-openplanner-session-rows! session-matches-page-actor-filter? upstream-page-size upstream-offset acc needed-count]
  (let [body (await (openplanner-client/sessions! (or (:openplanner-client config)
                                                      (openplanner-client/client config))
                                                  {:project (:session-project-name config)
                                                   :limit upstream-page-size
                                                   :offset upstream-offset}))
        page-rows (vec (or (:rows body) []))
        fetched-count (count page-rows)
        next-offset (+ upstream-offset fetched-count)
        upstream-has-more (boolean (:has_more body))
        allowed (await (authorized-session-ids! config ctx (map :session page-rows)))
        authorized-rows (->> page-rows
                             (filter #(contains? allowed (str (:session %))))
                             (filter #(interactive-session-id? (:session %)))
                             vec)
        actor-visible-rows (await (filter-page-actor-rows! config fetch-openplanner-session-rows! session-matches-page-actor-filter? actor-id exclude-actor-ids contract-id authorized-rows))
        next-acc (into acc actor-visible-rows)
        reached-target? (and (number? needed-count)
                             (>= (count next-acc) needed-count))]
    (cond
      reached-target? {:rows (vec (take needed-count next-acc))
                       :has_more true}
      (and upstream-has-more (pos? fetched-count))
      (await (fetch-authorized-session-pages! config ctx actor-id exclude-actor-ids contract-id authorized-session-ids! fetch-openplanner-session-rows! session-matches-page-actor-filter? upstream-page-size next-offset next-acc needed-count))
      :else {:rows next-acc
             :has_more false})))

(defn- hit-session-id
  [hit]
  (or (:session hit)
      (get-in hit [:metadata :session])
      (get-in hit [:extra :session])))

(defn ^:async search-hit-session-visibility!
  [config fetch-openplanner-session-rows! session-matches-page-actor-filter? actor-id exclude-actor-ids session-id]
  (try
    (let [rows (await (fetch-session-filter-rows! fetch-openplanner-session-rows! config session-id))]
      {:session session-id
       :visible (session-matches-page-actor-filter? config rows actor-id exclude-actor-ids)})
    (catch :default error
      (runtime/degraded! "memory-sessions/search-visibility"
                                :excluded error)
      {:session session-id
       :visible false})))

(defn ^:async filter-search-hits-by-actor!
  [config fetch-openplanner-session-rows! session-matches-page-actor-filter? actor-id exclude-actor-ids hits]
  (let [actor-id (memory-shape/normalized-actor-id actor-id)
        exclude-actor-ids (memory-shape/normalized-actor-ids exclude-actor-ids)
        hits (vec hits)]
    (if (and (str/blank? (str (or actor-id "")))
             (empty? exclude-actor-ids))
      hits
      (let [visibility! (partial search-hit-session-visibility!
                                 config fetch-openplanner-session-rows!
                                 session-matches-page-actor-filter? actor-id exclude-actor-ids)
            session-ids (->> hits
                             (keep hit-session-id)
                             (map str)
                             (remove str/blank?)
                             distinct
                             vec)
            results (await (runtime/all-results! (mapv visibility! session-ids)))
            allowed-sessions (->> results
                                  (filter :visible)
                                  (map (comp str :session))
                                  set)]
        (->> hits
             (filter (fn [hit]
                       (contains? allowed-sessions (str (or (hit-session-id hit) "")))))
             vec)))))

(defn- contract-only-admin-fetch?
  [ctx {:keys [actor-id exclude-actor-ids contract-id]}]
  (and contract-id
       (str/blank? (str (or actor-id "")))
       (empty? exclude-actor-ids)
       (system-admin? ctx)))

(defn fetch-memory-sessions-source!
  [config ctx opts authorized-session-ids! fetch-openplanner-session-rows! session-matches-page-actor-filter?]
  (cache/cached-memory-sessions-source!
   (:cache-key opts)
   (fn []
     (if (contract-only-admin-fetch? ctx opts)
       (fetch-contract-session-pages-from-mongo! config (:contract-id opts) (:needed-count opts))
       (fetch-authorized-session-pages! config ctx (:actor-id opts) (:exclude-actor-ids opts)
                                        (:contract-id opts) authorized-session-ids!
                                        fetch-openplanner-session-rows!
                                        session-matches-page-actor-filter?
                                        (:upstream-page-size opts) 0 [] (:needed-count opts))))))

