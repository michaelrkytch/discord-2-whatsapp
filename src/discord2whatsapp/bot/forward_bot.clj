(ns discord2whatsapp.bot.forward-bot
  (:require
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]
    [clojure.tools.reader.edn :as edn]
    [com.stuartsierra.component :as component]
    [discljord.connections :as c]
    [discljord.events :as e]
    [discljord.messaging :as m]
    [discord2whatsapp.bot.slash-commands :as commands]
    [discord2whatsapp.store :as store]
    [discord2whatsapp.wacomms :as wacomm]))

(def intents #{:guilds :guild-messages})
(def buffer-size 100)


;; -------------------------------
;; Component fns
;; -------------------------------

(defn send-text-message [bot channel-id text]
  (m/create-message! (:api-ch bot) channel-id :content text))

(defn get-channel [bot channel-id]
  (m/get-channel! (:api-ch bot) channel-id))

(defn forward-channel-message
  "Forward message from Discord to WA"
  [bot channel-id text]
  (log/debug "Attempting to forward message on " channel-id)
  (let [chat-jid (get (store/data (:store bot)) (str channel-id))]
    (when chat-jid
      ;; TODO decouple with another async channel
      (wacomm/forward-text-message (:wacomms bot) chat-jid text))))

;; -------------------------------
;; Handle WA events
;; -------------------------------

;; Forward a WA text message by putting a :whatsapp-message event on the
;; app event channel
(defn forward-wa-message
  "Default message handler.  Forward message if a mapping exits"
  [bot chat-jid text]
  (let [chat-mapping (store/data (:store bot))]
    (let [channel-id (bimap/get-backward chat-mapping (str chat-jid))
          ;; This is a subset of the message structure that the Discord gateway sends
          message {:author     {:bot false}
                   :channel-id channel-id
                   :content    text}]
      (when channel-id
        (a/>!! (:event-ch bot) [:forward-message message])))))


;; -------------------------------
;; Gateway message handlers
;; -------------------------------

(defn handle-message-create
  [bot
   _event-type
   {{:keys [bot-user username]} :author                     ;; map value of "author" may contain "bot" and "username"
    :keys                       [channel-id content]
    :as                         message}]
  (log/debug message)
  (when-not bot-user
    (log/debug "Received message from " username " on channel " channel-id "\n\"" content \")
    ;;(clojure.pprint/pprint message)
    (forward-channel-message bot channel-id content)))

(defn handle-interaction-create
  "Process interaction data and send back the response"
  [bot
   event-type
   {:keys [id token] :as event-data}]
  (when-some [{:keys [type data]} (commands/process-interaction-create event-type event-data (:wacomms bot) (:store bot))]
    (log/debug "type: " type ", data: " data)
    (m/create-interaction-response! (:api-ch bot) id token type :data data)))

;; A :whatsapp-message is posted internally by wa-comms
(defn handle-forward-message
  "Handle internally generated :whatsapp-message event by posting the message on the given Discord channel."
  [bot
   _event-type
   {{:keys [bot-user _username]} :author                    ;; map value of "author" may contain "bot" and "username"
    :keys                        [channel-id content]
    :as                          message}]
  (log/debug "WA message:\n" message)
  (when-not bot-user
    (send-text-message bot channel-id content)))

;; -------------------------------
;; Bot component
;; -------------------------------

(defn handler-dispatch-fn
  "Returns a dispatch function that takes event-type and event-data as parameters and
   calls event handlers from `handlers`, dispatching on event-type, and
   passing bot, event-type, event-data"
  [bot handlers]
  (fn [event-type event-data]
    (log/debug "Dispatching on event type " event-type)
    (doseq [f (handlers event-type)]
      (f bot event-type event-data))))

(defn start-listening [bot event-ch]
  (if event-ch
    ;; TODO Consider using a/pipeline to process the event channel rather than e/message-pump!
    (let [handlers {:message-create     [#'handle-message-create]
                    :interaction-create [#'handle-interaction-create]
                    :forward-message    [#'handle-forward-message]}
          dispatch-fn (handler-dispatch-fn bot handlers)]
      (log/info "Starting event loop")
      (e/message-pump! event-ch dispatch-fn)
      (log/info "Exiting event loop"))
    ;; else
    (log/error "Event channel not initialized")))

(defrecord Bot [config                                      ; parameters
                wacomms store                               ; dependencies
                api-ch event-ch gateway-ch event-loop-fut   ; state
                ]
  component/Lifecycle

  (start [this]
    (let [token (:token config)
          event-ch (a/chan buffer-size)
          gateway-ch (c/connect-bot! token event-ch :intents intents)
          api-ch (m/start-connection! token)
          pre-start-bot (into this {:config     config
                                    :event-ch   event-ch
                                    :gateway-ch gateway-ch
                                    :api-ch     api-ch})]
      ;; Start the event loop, passing the bot with all it's channels initialized
      ;; And then store the event loop future another component field
      (assoc pre-start-bot :event-loop-fut (future (start-listening pre-start-bot event-ch)))
      ))

  (stop [this]
    (a/>!! event-ch [:disconnect :disconnect])              ;; Tell message pump to stop
    (m/stop-connection! api-ch)
    (c/disconnect-bot! gateway-ch)
    (a/close! event-ch)
    (reduce #(assoc %1 %2 nil) this [:event-ch :gateway-ch :api-ch :event-loop-fut])))

(defn new-bot [config]
  (map->Bot {:config config}))

(defn join
  "Block until bot shuts down"
  [bot]
  (deref (:event-loop-fut bot)))


(comment
  (def cfg (edn/read-string (slurp "config.edn")))

  (require '[discord2whatsapp.store :as st])

  ;; Bot with it's store dependency but without the wacomms dependency
  (def test-sys
    (component/system-map
      :store (st/new-store (str (:storage-dir cfg) "/discord2whatsapp.edn"))
      :bot (component/using
             (new-bot cfg)
             [:store])))

  (alter-var-root #'test-sys component/start)

  ;; Register commands
  (require '[discord2whatsapp.bot.slash-commands :as commands])
  ;; TODO where do we get guild-id from in general usage?
  (let [guild-id 1063955412086435860
        app-id (get-in bot [:config :app-id])
        api-ch (:api-ch bot)]
    (commands/register-wa-commands! app-id api-ch guild-id commands/slash-commands))

  (alter-var-root #'test-sys component/stop)
  ,)

