(ns discord2whatsapp.core
  (:require [bimap]
            [discord2whatsapp.bot.forward-bot :as dcomm]
            [discord2whatsapp.store :as store]
            [discord2whatsapp.wacomms :as wacomm]
            [com.stuartsierra.component :as component]))

(defn -main [& args]
  )

(comment
  (def system
    (component/system-map
      :store (store/new-store "/Users/michael/tmp/discord2whatsapp-store.edn")
      :wacomms (wacomm/new-wacomms)
      :bot (component/using
             (dcomm/new-bot "config.edn")
             [:wacomms :store])
      ))
  (alter-var-root #'system component/start-system)
  ;; This is a separate step to avoid a circular dependency between wacomms and bot
  (wacomm/add-message-listener (:wacomms system) (partial dcomm/forward-wa-message (:bot system)))
  (alter-var-root #'system component/stop-system)
  )