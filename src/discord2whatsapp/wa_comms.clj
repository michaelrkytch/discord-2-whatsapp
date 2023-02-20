(ns discord2whatsapp.wa-comms
  (:require [discord2whatsapp.store :as store]
            [clojure.core.async :as a]
            [bimap :refer [get-backward]])
  (:import
    [it.auties.whatsapp.api Whatsapp]
    [it.auties.whatsapp.listener OnLoggedIn OnWhatsappNewMessage]
    [it.auties.whatsapp.model.contact ContactJid]
    [it.auties.whatsapp.model.info ContextInfo]
    [it.auties.whatsapp.model.message.standard TextMessage]))

(defonce whatsapp-api (atom nil))

(defn message-text [msg-info]
  (try
    (-> msg-info
        .message
        .content
        .text)
    (catch Exception e
      ;; TODO log
      (println "Ignoring exception " (.getMessage e)))))

(defn message-chat-jid [msg-info]
  (-> msg-info
      .key
      .chatJid))

(defn message-forwarded? [msg-info]
  (let [message-opt (.. msg-info (message) (contentWithContext))]
    (when (.isPresent message-opt)
      (.. message-opt (get) (contextInfo) (forwarded)))))

;; Seems to only return chats which have been active in the session
(defn list-chats [& [filter-fn]]
  (let [chats (-> @whatsapp-api
                  .store
                  .chats
                  .iterator
                  iterator-seq)]
    (if filter-fn
      (filter filter-fn chats)
      chats)))

(defn chat-by-jid [jid-str]
  (let [jid (ContactJid/of jid-str)
        optional-chat (-> @whatsapp-api
                          .store
                          (.findChatByJid jid))]
    (when (.isPresent optional-chat)
      (.get optional-chat))))

(defn chat-by-name [name]
  (let [optional-chat (-> @whatsapp-api
                          .store
                          (.findChatByName name))]
    (when (.isPresent optional-chat)
      (.get optional-chat))))

(defn disconnect []
  (println "disconnect!!")
  (-> @whatsapp-api
      .disconnect
      .join))

(defn send-wa-message
  "Send a WA message, passing Chat and Message objects"
  [chat message]
  (.sendMessage @whatsapp-api chat message))

(defn send-text-message [chat-jid-str text]
  (if-let [chat (chat-by-jid chat-jid-str)]
    (send-wa-message chat text)))

(defn forward-text-message
  "Send a text message marked as 'forwarded'"
  [chat-jid-str msg-text]
  (if-let [chat (chat-by-jid chat-jid-str)]
    (let [context-info (.. (ContextInfo.) (forwarded true))
          message (.. (TextMessage.)
                      (text msg-text)
                      (contextInfo context-info))]
      (send-wa-message chat message))))

;; Forward a WA text message by putting a :whatsapp-message event on the
;; app event channel
(defn process-text-message
  "Default message handler.  Forward message if a mapping exits"
  [event-ch chat-jid text]
  (let [chat-mapping (store/chat-mapping)]
    (let [channel-id (get-backward chat-mapping (str chat-jid))
               ;; This is a subset of the message structure that the Discord gateway sends
               message {:author {:bot false}
                        :channel-id channel-id
                        :content text}]
      (when channel-id
        (a/>!! event-ch [:whatsapp-message message])))))

;; TODO: switch on message type
(defn handle-message [event-ch msg-info]
  (when-not (message-forwarded? msg-info)                   ;; Ignore forwarded messages
    (when-let [text (message-text msg-info)]
      (let [jid (message-chat-jid msg-info)]
        (condp = text
          "/disconnect" (disconnect)
          "/test" (send-text-message jid "I got your test message.")
          ;; else default message processing
          (process-text-message event-ch jid text)
          )))))

(defn init-api [event-ch]
  ;; TODO deal with event-ch
  "Create a new instance of the Whatsapp API.
  `event-ch is a core.async channel on which we can feed messages to the discord message processor"
  (let [login-callback
        (reify OnLoggedIn
          (onLoggedIn [_] (println "Connected!!!")))

        on-message
        (reify OnWhatsappNewMessage
          (onNewMessage [_ whatsapp msg-info]
            (println "Got WhatsApp message!!")
            (handle-message event-ch msg-info)))

        api (-> (Whatsapp/lastConnection)
                (.addLoggedInListener login-callback)
                (.addNewMessageListener on-message))]
    (if api
      (reset! whatsapp-api api)
      ;; else
      (println "Initialization failed!"))))

(defn connect
  "Connect to WhatsApp API and return a CompletableFuture that completes when the websocket is disconnected." []
  (.connect @whatsapp-api))

(defn -main [& args]
  (init-api)
  (.get (connect)))