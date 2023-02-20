(ns discord2whatsapp.core
  (:require  [bimap]
             [discord2whatsapp.store :as store]
             [discord2whatsapp.discord-comms :as dcomm]
             [discord2whatsapp.wa-comms :as wacomm]))

(defn -main [& args]
  (let [dcomm-config (dcomm/init "config.edn")
        evt-ch (:event-ch dcomm-config)
        _ (wacomm/init-api evt-ch)
        evt-loop-p (future (dcomm/start-listening))         ;; Clojure future
        wa-future (wacomm/connect)                          ;; Java CompletableFuture
        ]))

(comment
  (def dcomm-config (dcomm/init "config.edn"))
  (def evt-ch (:event-ch dcomm-config))
  (def wa-ret (wacomm/init-api evt-ch))
  (def evt-loop-p (future (dcomm/start-listening)))         ;; Clojure future
  (def wa-future (wacomm/connect))

  )