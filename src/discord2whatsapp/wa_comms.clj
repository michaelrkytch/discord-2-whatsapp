(ns discord2whatsapp.wa-comms
  (:import
    [it.auties.whatsapp.api Whatsapp]
    [it.auties.whatsapp.listener OnLoggedIn OnWhatsappNewMessage]
    [it.auties.whatsapp.model.contact ContactJid]))

(def whatsapp-api (atom nil))

(defn message-text [msg-info]
  (-> msg-info
      .message
      .content
      .text
      .toLowerCase))

(defn message-chat-jid [msg-info]
  (-> msg-info
      .key
      .chatJid))

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

(defmulti send-text-message
          "Dispatch send-text-message based on class of target (ChatJid or String)"
          (fn [target text] (class target)))

(defmethod send-text-message ContactJid [chat-jid text]
  (.sendMessage @whatsapp-api chat-jid text))

(defmethod send-text-message String [chat-name text]
  (if-let [chat (chat-by-name chat-name)]
    (.sendMessage @whatsapp-api chat text)))

(defn handle-message [msg-info]
  (when-let [text (message-text msg-info)]
    (let [jid (message-chat-jid msg-info)]
      (condp = text
        "/disconnect" (disconnect)
        "/test" (send-text-message jid "I got your test message.")
        ;; else
        (println "From: " jid " -- Ignored message " text)
        ))))

(defn init-api []
  "Create a new instance of the Whatsapp API"
  (let [login-callback
        (reify OnLoggedIn
          (onLoggedIn [_] (println "Connected!!!")))

        on-message
        (reify OnWhatsappNewMessage
          (onNewMessage [_ whatsapp msg-info]
            (println "Got message!!")
            (handle-message msg-info)))

        api (-> (Whatsapp/lastConnection)
                (.addLoggedInListener login-callback)
                (.addNewMessageListener on-message))]
    (if api
      (reset! whatsapp-api api)
      (println "Initialization failed!"))))

(defn -main [& args]
  (let [api (init-api)]
    (-> api
        .connect
        .get)))