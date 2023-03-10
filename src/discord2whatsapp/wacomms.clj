(ns discord2whatsapp.wacomms
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component])
  (:import
    (it.auties.whatsapp.api QrHandler QrHandler$ToFileConsumer)
    (it.auties.whatsapp.listener Listener OnLoggedIn OnWhatsappNewMessage)
    (it.auties.whatsapp.api Whatsapp Whatsapp$Options)
    (it.auties.whatsapp.model.contact ContactJid)
    (it.auties.whatsapp.model.info ContextInfo MessageInfo)
    (it.auties.whatsapp.model.message.standard TextMessage)
    (java.nio.file FileSystems)))

(defn message-text [msg-info]
  (try
    (-> msg-info
        .message
        .content
        .text)
    (catch Exception e
      (log/info "Ignoring exception " (.getMessage e)))))

(defn message-chat-jid [msg-info]
  (-> msg-info
      .key
      .chatJid))

(defn message-forwarded? [msg-info]
  (let [message-opt (.. msg-info (message) (contentWithContext))]
    (when (.isPresent message-opt)
      (.. message-opt (get) (contextInfo) (forwarded)))))

;-----------------------------
; Component functions
;-----------------------------

(defn list-chats [wacomms & [filter-fn]]
  (let [chats (-> (:whatsapp-api wacomms)
                  .store
                  .chats
                  .iterator
                  iterator-seq)]
    (if filter-fn
      (filter filter-fn chats)
      chats)))

(defmulti chat-by-jid (fn [_wacomms jid] (class jid)))
(defmethod chat-by-jid ContactJid [wacomms jid]
  (-> (:whatsapp-api wacomms)
      .store
      (.findChatByJid jid)
      (.orElse nil)))

(defmethod chat-by-jid String [wacomms jid-str]
  (chat-by-jid wacomms (ContactJid/of jid-str)))

;(defn chat-by-name
;  "Resolves the given chat name into a Chat object"
;  [wacomms name]
;  (let [optional-chat (-> (:whatsapp-api wacomms)
;                          .store
;                          (.findChatByName name))]
;    (when (.isPresent optional-chat)
;      (.get optional-chat))))

(defn disconnect
  "Disconnect asynchronously from WA websocket.  Returns a java Future representing the disconnect operation."
  [wacomms]
  (log/debug "disconnect!!")
  (.disconnect (:whatsapp-api wacomms)))

(defn join
  "Block until the WA websocket is closed"
  [wacomms]
  (deref (:websocket-future wacomms)))
(defn send-wa-message
  "Send a WA message, passing Chat and Message objects"
  [wacomms chat message]
  (.sendMessage (:whatsapp-api wacomms) chat message))

(defn- send-text-message [wacomms chat-jid text]
  (if-let [chat (chat-by-jid wacomms chat-jid)]
    (send-wa-message wacomms chat text)))

(defn forward-text-message
  "Send a text message marked as 'forwarded'"
  [wacomms chat-jid-str msg-text]
  (if-let [chat (chat-by-jid wacomms chat-jid-str)]
    (let [context-info (.. (ContextInfo.) (forwarded true))
          message (.. (TextMessage.)
                      (text msg-text)
                      (contextInfo context-info))]
      (send-wa-message wacomms chat message))))

(defn handle-wa-message
  "Dispatch incoming WA messages.  The default is to delegate message handling to the given
  message-handler function, passing jid and content as arguments"
  [wacomms msg-info message-handler]
  (when-not (message-forwarded? msg-info)            ;; Ignore forwarded messages
    ;; TODO: use polymorphic process-message rather than extracting text here
    (when-let [text (message-text msg-info)]
      (let [jid (message-chat-jid msg-info)]
        (condp = text
          "/disconnect" (disconnect wacomms)
          "/test" (send-text-message  wacomms jid "I got your test message.")
          ;; else default message processing
          (message-handler jid text))))))

(defn init-api
  "Create a new instance of the Whatsapp API."
  [qr-file-path]
  (let [login-callback
        (reify OnLoggedIn
          (onLoggedIn [_] (log/debug "WhatsApp websocket connected!!!")))

        qr-handler (QrHandler/toFile
                     qr-file-path
                     (reify QrHandler$ToFileConsumer
                       (accept [this path] (log/info "qr file path " path))))

        options (-> (Whatsapp$Options/defaultOptions)
                    (.qrHandler qr-handler))

        api (-> (Whatsapp/lastConnection options)
                (.addLoggedInListener login-callback))]
    api))

(defn add-message-listener
  "Register 'handler' as default handler for accepted WA messages.
  'handler' takes a WA chat jid and some content value as arguments"
  [wacomms handler]
  (let [on-message
        (reify OnWhatsappNewMessage
          (^void onNewMessage [^Listener _ ^Whatsapp _ ^MessageInfo msg-info]
            (log/debug "Got WhatsApp message!!")
            (handle-wa-message wacomms msg-info handler)))]
    (.addNewMessageListener (:whatsapp-api wacomms) on-message)))

;------------------------------
; Component
;------------------------------
(defn qr-file-path
  "Returns the path to the qr file, as a Path, which will only be populated after the API is connected."
  [storage-dir]
  (-> (FileSystems/getDefault)
      (.getPath storage-dir (into-array ["qr.jpg"]))))

(defrecord WhatsAppComms [storage-dir whatsapp-api websocket-future]
  component/Lifecycle
  (start [this]
    (let [api (init-api (qr-file-path storage-dir))
          fut (.connect api)]
      (into this {:whatsapp-api api
                  :websocket-future fut})))
  (stop [this]
    (disconnect this)
    (join this)
    (map #(assoc this % nil) [:whatsapp-api :websocket-future])))

(defn new-wacomms [storage-dir]
  (map->WhatsAppComms {:storage-dir storage-dir}))


(comment
  (def wa (new-wacomms ""))
  (alter-var-root #'wa component/start)

  (require '[clojure.edn :as edn])
  (def storage-dir (-> (slurp "config.edn")
                       edn/read-string
                       :storage-dir))
  )




