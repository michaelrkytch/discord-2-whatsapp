(ns discord2whatsapp.core
  (:require  [bimap]
             [discord2whatsapp.store :as store]
             [discord2whatsapp.discord-comms :as dcomm]
             [discord2whatsapp.wa-comms :as wacomm]))

(defn -main [& args]
  (wacomm/init-api)
  (dcomm/init "config.edn")
  (let [evt-loop-p (future (dcomm/start-listening))         ;; Clojure future
        wa-future (wacomm/connect)                          ;; Java CompletableFuture
        ]))
