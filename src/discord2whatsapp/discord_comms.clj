(ns discord2whatsapp.discord-comms
  (:require
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]
    [discljord.connections :as c]
    [discljord.events :as e]
    [discljord.messaging :as m]
    [discord2whatsapp.store :as store]
    [discord2whatsapp.wa-comms :as wacomm]
    [discord2whatsapp.slash-commands :as commands]))

(def intents #{:guilds :guild-messages})
(def buffer-size 100)
(defonce config (atom nil))

(defn send-text-message [channel-id text]
  (m/create-message! (:api-ch @config) channel-id :content text))

(defn get-channel [channel-id]
  (m/get-channel! (:api-ch @config) channel-id))

(defn disconnect []
  (when-let [{:keys [event-ch gateway-ch api-ch]} @config]
    (m/stop-connection! api-ch)
    (c/disconnect-bot! gateway-ch)
    (a/close! event-ch)))

(defn forward-channel-message [channel-id text]
  (println "Attempting to forward message on " channel-id)
  (let [chat-jid (get (store/chat-mapping) (str channel-id))]
    (when chat-jid
      (wacomm/forward-text-message chat-jid text))))

;; -------------------------------
;; Gateway interactions
;; -------------------------------

(defn handle-message-create
  [_                                                        ;; discard event-type
   {{:keys [bot username]} :author                          ;; map value of "author" may contain "bot" and "username"
    :keys                  [channel-id content]
    :as                    message}]
  (println message)
  (when-not bot
    (condp = content
      "!!disconnect!!" (disconnect)
      "!!stop!!" (a/>!! (:event-ch @config) [:disconnect :disconnect])
      ;; else normal message processing
      (do
        (println "Received message from " username " on channel " channel-id "\n\"" content \")
        ;;(clojure.pprint/pprint message)
        (forward-channel-message channel-id content)))))

(defn handle-interaction-create
  "Process interaction data and send back the response"
  [event-type {:keys [id token] :as event-data}]
  (when-some [{:keys [type data]} (commands/process-interaction-create event-type event-data)]
    (println "type: " type ", data: " data)
    (m/create-interaction-response! (:api-ch @config) id token type :data data)))

;; A :whatsapp-message is posted internally by wa-comms
(defn handle-wa-message
  [_                                                        ;; discard event-type
   {{:keys [bot username]} :author                          ;; map value of "author" may contain "bot" and "username"
    :keys                  [channel-id content]
    :as                    message}]
  (println "WA message:\n" message)
  (when-not bot
    (send-text-message channel-id content)))

(defn start-listening []
  (if-let [event-ch (:event-ch @config)]
    ;; TODO Consider using a/pipeline to process the event channel rather than e/message-pump!
    (let [dispatch-fn (partial e/dispatch-handlers
                               {:message-create     [#'handle-message-create]
                                :interaction-create [#'handle-interaction-create]
                                :whatsapp-message   [#'handle-wa-message]})]
      (log/info "Starting event loop")
      (e/message-pump! event-ch dispatch-fn)
      (log/info "Exiting event loop"))
    ;; else
    (log/error "Event channel not initialized")))

(defn- init-api
  "Open connection to the Discord websocket API.
`token` is the bot token
  "
  [token]
  (let [event-ch (a/chan buffer-size)
        gateway-ch (c/connect-bot! token event-ch :intents intents)
        api-ch (m/start-connection! token)]
    (swap! config merge {:event-ch   event-ch
                         :gateway-ch gateway-ch
                         :api-ch     api-ch})))

(defn init
  "Read configuration and initialize bot.  Call `start-listening` to start the event loop.
  Returns the current config map."
  [config-file]
  (when-let [cfg (clojure.edn/read-string (slurp config-file))]
    (do
      (reset! config cfg)
      (init-api (:token cfg)))))

(defn -main [& args]
  (if (init "config.edn")
    (start-listening)
    ;; else
    (println "Unable to load config.edn")))

(comment
  (def guild-id 1063955412086435860)
  (init "config.edn")
  (def evt-loop-p (future (start-listening)))
  )
