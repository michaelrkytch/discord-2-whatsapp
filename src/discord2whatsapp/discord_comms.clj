(ns discord2whatsapp.discord-comms
  (:require
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]
    [discljord.connections :as c]
    [discljord.events :as e]
    [discljord.messaging :as m]
    [slash.command.structure :as slash]
    [slash.response :as resp]))

(def intents #{:guilds :guild-messages})
(def buffer-size 100)
(def config (atom nil))

(def waconnect-command
  (slash/command "waconnect"
                 "Connect a WhatsApp chat to this channel"
                 :options [(slash/option "chat-name" "WhatsApp chat name" :string :required true)]))

(defn send-text-message [channel-id text]
  (m/create-message! (:api-ch @config) channel-id :content text))

(defn init-api
  "Open connection to the Discord websocket API.
`token` is the bot token
  "
  [token]
  (let [event-ch      (a/chan buffer-size)
        gateway-ch    (c/connect-bot! token event-ch :intents intents)
        api-ch        (m/start-connection! token)]
    (swap! config merge {:event-ch   event-ch
                         :gateway-ch gateway-ch
                         :api-ch     api-ch})))

(defn disconnect []
  (when-let [{:keys [event-ch gateway-ch api-ch]} @config]
    (m/stop-connection! api-ch)
    (c/disconnect-bot! gateway-ch)
    (a/close!           event-ch)))

(defn handle-message-create
  [_                                                        ;; discard event-type
   {{:keys [bot username]} :author                          ;; map value of "author" may contain "bot" and "username"
    :keys                  [channel-id content]
    :as message}]
  (when-not bot
    (condp = content
      "!!disconnect!!" (disconnect)
      "!!stop!!" (a/>!! (:event-ch @config) [:disconnect :disconnect])
      ;; else normal message processing
      (do
        (println "Received message from " username " on channel " channel-id "\n\"" content \")
        (clojure.pprint/pprint message)))))

(defn process-interaction-create
  "Transform the interaction data into response data"
  [_
   {:keys [type guild-id channel-id]
    {command-name :name
     [{value :value}] :options} :data
    :as interaction}
   ]
  (println "Received interaction " command-name)
  (clojure.pprint/pprint interaction)
  (when (= command-name "waconnect")
    (resp/ephemeral
      (resp/channel-message
        {:content (str "Someday this might connect you to " value)}))))

(defn handle-interaction-create
  "Process interaction data and send back the response"
  [event-type {:keys [id token] :as event-data}]
  (when-some [{:keys [type data]} (process-interaction-create event-type event-data)]
    (println "type: " type ", data: " data)
    (m/create-interaction-response! (:api-ch @config) id token type :data data)))

(defn register-wa-connect! [guild-id & {force-reg :force :or {force-reg false}}]
    (let [{:keys [app-id api-ch]} @config
          {:keys [name description options]} waconnect-command
          already-registered? (fn []
                                (->> (m/get-guild-application-commands! api-ch app-id guild-id)
                                     deref
                                     (map :name)
                                     (some #{"waconnect"})))]
      (when (or force-reg (already-registered?))
        (m/create-guild-application-command!
          api-ch
          app-id
          guild-id
          name
          description
          :options options))))

(defn start-listening []
  (if-let [event-ch (:event-ch @config)]
    ;; TODO Consider using a/pipeline to process the event channel rather than e/message-pump!
    (let [dispatch-fn (partial e/dispatch-handlers
                               {:message-create     [#'handle-message-create]
                                :interaction-create [#'handle-interaction-create]})]
      (log/info "Starting event loop")
      (e/message-pump! event-ch dispatch-fn)
      (log/info "Exiting event loop"))
    ;; else
    (log/error "Event channel not initialized")))

(defn init
  "Read configuration and initialize bot.  Call `start-listening` to start the event loop."
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
