(ns knoxx.backend.extern.agent-session-fixture
  "Complete opaque provider-session fixtures for lifecycle persistence tests.

   Every supported operation is an explicit original callback. Unsupported
   operations throw instead of gaining successful defaults. The opaque type
   retains session identity; it does not introduce record value equality."
  (:require [knoxx.backend.shape.agent :as agent]))

(defn- invoke! [callbacks operation session arguments]
  (if-let [callback (get callbacks operation)]
    (apply callback session arguments)
    (throw (ex-info (str "Unexpected agent session fixture operation: " (name operation))
                    {:operation operation}))))

(deftype Session [callbacks]
  agent/IAgentSession
  (streaming? [session]
    (invoke! callbacks :streaming? session []))
  (current-turn [session]
    (invoke! callbacks :current-turn session []))
  (messages [session]
    (invoke! callbacks :messages session []))
  (subscribe! [session argument1]
    (invoke! callbacks :subscribe! session [argument1]))
  (send-user-message! [session argument1]
    (invoke! callbacks :send-user-message! session [argument1]))
  (follow-up! [session argument1]
    (invoke! callbacks :follow-up! session [argument1]))
  (steer! [session argument1]
    (invoke! callbacks :steer! session [argument1]))
  (set-thinking-level! [session argument1]
    (invoke! callbacks :set-thinking-level! session [argument1]))
  (abort! [session]
    (invoke! callbacks :abort! session [])))

(defn session
  "Create one identity-bearing complete port from the original method callbacks.
   Each callback receives this actual session receiver and unchanged arguments;
   return values, promises and exceptions pass through without wrapping."
  [callbacks]
  (Session. callbacks))
