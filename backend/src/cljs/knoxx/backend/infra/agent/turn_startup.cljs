(ns knoxx.backend.infra.agent.turn-startup
  "Owned durable startup and final disclosure before the provider prompt."
  (:require [knoxx.backend.domain.agent.content :as content :refer [model-ready-content-parts]]
            [knoxx.backend.domain.time :refer [now-iso]]
            [knoxx.backend.extern.agent-turn-node :as xturn-node]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.agent.initial-admission :as initial-admission]
            [knoxx.backend.infra.agent.session :refer [prune-session-messages]]
            [knoxx.backend.infra.agent.stream :as stream]
            [knoxx.backend.infra.agent.transcript :as transcript]
            [knoxx.backend.infra.character.turn-context :as character-context]
            [knoxx.backend.infra.stores.mongo-session-store :as session-store]))

(defn- ^:async persist-running-session-update!
  [session-id conversation-id run-id persisted-request-messages]
  (try
    (await (session-store/update-session! session-id
                                          {:status "running"
                                           :has_active_stream false
                                           :messages persisted-request-messages
                                           :conversation_id conversation-id
                                           :run_id run-id}))
    (catch :default err
      (xturn-node/log-session-update-failure! session-id (ex-message err)))))

(defn- prepare-running-turn [config params materialized-content-parts]
  (let [{:keys [model-id agent-spec seeded-messages message]} params
        materialized-content-parts (vec (or materialized-content-parts []))
        turn-message (content/nonblank message)
        user-message (if (seq materialized-content-parts)
                       {:role "user" :content turn-message :content-parts materialized-content-parts}
                       {:role "user" :content turn-message})]
    {:user-message user-message :turn-message turn-message
     :prompt-content-parts (model-ready-content-parts config model-id materialized-content-parts)
     :request-messages (prune-session-messages agent-spec (conj seeded-messages user-message))}))

(defn- ^:async emit-turn-hydration! [publish! run-id conversation-id session-id agent-spec passive memory]
  (await (character-context/emit-inclusion! run-id conversation-id session-id agent-spec))
  (await (publish! run-id conversation-id session-id passive memory)))

(defn- ^:async settle-disclosure-refusal! [finalize-failure! config session params persisted memory]
  (let [{:keys [run-id conversation-id session-id started-ms agent-spec event-stream-sink]} params
        safe-spec (dissoc agent-spec :decision-encounters)
        safe-memory (hydration/retain-current-graph-hydration memory nil)
        state (assoc (stream/make-stream-state run-id conversation-id session-id (now-iso) started-ms xturn-node/random-uuid!)
                     :event-stream-sink event-stream-sink)
        error (ex-info "Character disclosure authority unavailable" {:reason :character-disclosure-authority-unavailable})]
    ;; Nothing has been sent or registered as an active provider turn. Startup
    ;; and hydration publication have settled; terminal cleanup owns this sink.
    (character-context/retain-resources! run-id safe-spec nil safe-memory)
    (await (finalize-failure! config state session run-id conversation-id session-id started-ms
                                   nil safe-memory persisted safe-spec error))))

(defn- ^:async final-disclosure-or-refuse! [finalize-failure! runtime config session params auth-context persisted memory]
  (let [disclosed (await (character-context/capture-prompt! runtime config (:agent-spec params) auth-context memory))]
    (if (:refused? disclosed)
      (await (settle-disclosure-refusal! finalize-failure! config session params persisted (:memory disclosed)))
      disclosed)))

(defn- ^:async admit-prepared-turn!
  [publish! runtime config params prepared persisted passive memory]
  (let [{:keys [run-id session-id conversation-id started-at model-id mode thinking-level
                agent-spec auth-extra auth-context startup-owner event-stream-sink]} params
        disclosed* (atom nil)]
    (await (initial-admission/create-run!
            {:conversation-id conversation-id :startup-owner startup-owner :sink event-stream-sink}
            [run-id session-id conversation-id started-at model-id mode thinking-level
             agent-spec auth-extra (:request-messages prepared) config]
            (^:async fn []
              (await (persist-running-session-update! session-id conversation-id run-id persisted))
              (let [disclosed (await (character-context/capture-prompt! runtime config agent-spec auth-context memory))]
                (reset! disclosed* disclosed)
                (await (emit-turn-hydration! publish! run-id conversation-id session-id (:agent-spec disclosed)
                                             passive (:memory disclosed)))))))
    @disclosed*))

(defn ^:async start!
  "Admit startup, settle persisted hydration, and recheck disclosure before prompt IO."
  [runtime config params [passive memory materialized-content-parts session]
   {:keys [install-event-sink! publish-hydration! finalize-failure! prompt!]}]
  (let [{:keys [run-id session-id conversation-id started-ms model-id mode agent-spec auth-context]} params
        prepared (prepare-running-turn config params materialized-content-parts)
        persisted (prune-session-messages agent-spec (transcript/transcript-before-prompt session (:user-message prepared) agent-spec))
        params (assoc params :event-stream-sink (install-event-sink! config))
        published (await (admit-prepared-turn! publish-hydration! runtime config params prepared persisted passive memory))
        disclosed (if (:refused? published)
                    (await (settle-disclosure-refusal! finalize-failure! config session params persisted (:memory published)))
                    (await (final-disclosure-or-refuse! finalize-failure! runtime config session params auth-context persisted (:memory published))))]
    ;; Persistence awaits have settled; disclose the whole graph/direct context once more.
    (character-context/retain-resources! run-id (:agent-spec disclosed) passive (:memory disclosed))
    (await (prompt! config session-id run-id conversation-id started-ms model-id mode
                    session (:turn-message prepared) (:prompt-content-parts prepared) passive (:memory disclosed)
                    persisted (:agent-spec disclosed) (:event-stream-sink params)))))
