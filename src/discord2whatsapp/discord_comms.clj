(ns discord2whatsapp.discord-comms
  (:require
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]
    [discljord.connections :as c]
    [discljord.events :as e]
    [discljord.messaging :as m]
    [slash.command.structure :as slash]
    [slash.response :as resp]
    [discord2whatsapp.store :as store]
    [discord2whatsapp.wa-comms :as wacomm]))

(def intents #{:guilds :guild-messages})
(def buffer-size 100)
(def config (atom nil))

(def wa-connect-command
  (slash/command "wa-connect"
                 "Connect a WhatsApp chat to this channel"
                 :options [(slash/option "chat-name" "WhatsApp chat name" :string :required true)]))

(def wa-list-chats-command
  (slash/command "wa-list-chats" "List all chats for this user"))

(def commands [wa-connect-command wa-list-chats-command])

(defn send-text-message [channel-id text]
  (m/create-message! (:api-ch @config) channel-id :content text))

(defn interaction-response
  "Create an interaction response.  Pass :ephemeral true for ephemeral message."
  [text & {ephemeral :ephemeral}]
  (let [msg (resp/channel-message {:content text})]
    (if ephemeral
      (resp/ephemeral msg)
      msg)))

(defn get-channel [channel-id]
  (m/get-channel! (:api-ch @config) channel-id))

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

;; TODO: This should live in core, and we should communicate via async channel
(defn forward-channel-message [channel-id text]
  (let [channel-name (:name (get-channel channel-id))
        chat-name (get (store/chat-mapping) channel-id)]
    (when chat-name
      (wacomm/send-text-message chat-name text))))


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
        ;;(clojure.pprint/pprint message)
        (forward-channel-message channel-id content)))))

(defn handle-wa-connect [channel-id text]
  (if (store/store-mapping channel-id text)
    (interaction-response (str "Connected channel " channel-id " to chat " text) :ephemeral true))
  ;; else
  (interaction-response (str "Unable to connect to " text) :ephemeral true))

(defn chat-list-filter [chat]
  (and (.. chat (createdBy) (isPresent))
       (not (.archived chat))))

(defn handle-wa-list-chats
  "Returns a response containing the names of all chats that meet the criteria of `chat-list-filter`"
  []
  (let [max-response-chars 2000
        chats (wacomm/list-chats chat-list-filter)
        chat-display (map #(str (.name %) "\t(" (.jid %) ")") chats)]
    (if (seq chat-display)
      (->> chat-display
           (reduce #(str %1 "\n" %2))
           ;; truncate if necessary
           (#(if (> (count %) max-response-chars)
               (subs % 0 max-response-chars)
               %))
           interaction-response)
      ;; else
      (interaction-response "No known WhatsApp chats for user."))))

(defn process-interaction-create
  "Transform the interaction data into response data"
  [_
   {:keys [type guild-id channel-id]
    {command-name :name
     [{value :value}] :options} :data
    :as interaction}
   ]
  (println "Received interaction " command-name)
  ;;(clojure.pprint/pprint interaction)
  (try
    (condp = command-name
      "wa-connect" (handle-wa-connect channel-id value)
      "wa-list-chats" (handle-wa-list-chats)
      ;; else
      (interaction-response (str "I don't know the command '" command-name \') :ephemeral true))
    (catch Exception e
      ;; TODO log
      (interaction-response "Error processing command" :ephemeral true))))

(defn handle-interaction-create
  "Process interaction data and send back the response"
  [event-type {:keys [id token] :as event-data}]
  (when-some [{:keys [type data]} (process-interaction-create event-type event-data)]
    (println "type: " type ", data: " data)
    (m/create-interaction-response! (:api-ch @config) id token type :data data)))

(defn register-wa-commands! [guild-id commands & {force-reg :force :or {force-reg false}}]
    (let [{:keys [app-id api-ch]} @config
          registered-commands (->> (m/get-guild-application-commands! api-ch app-id guild-id)
                                   deref
                                   (map :name))
          ]
      (doseq [command commands]
        (let [{:keys [name description options]} command]
          (when (or force-reg (not-any? #(= name %) registered-commands))
            (println "Registering " name)
            (m/create-guild-application-command!
              api-ch
              app-id
              guild-id
              name
              description
              :options options))))))

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
