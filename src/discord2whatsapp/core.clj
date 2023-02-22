(ns discord2whatsapp.core
  (:require [bimap]
            [clojure.core.async :as a]
            [discord2whatsapp.discord-comms :as dcomm]
            [discord2whatsapp.store :as store]
            [discord2whatsapp.wa-comms :as wacomm]))

;; -------------------------------
;; Handle WA events
;; -------------------------------

;; Forward a WA text message by putting a :whatsapp-message event on the
;; app event channel
(defn process-wa-text-message
  "Default message handler.  Forward message if a mapping exits"
  [event-ch chat-jid text]
  ;; TODO can we remove the dependency on store?
  (let [chat-mapping (store/chat-mapping)]
    (let [channel-id (bimap/get-backward chat-mapping (str chat-jid))
          ;; This is a subset of the message structure that the Discord gateway sends
          message {:author     {:bot false}
                   :channel-id channel-id
                   :content    text}]
      (when channel-id
        (a/>!! event-ch [:whatsapp-message message])))))

(defn handle-wa-message [event-ch msg-info]
  (when-not (wacomm/message-forwarded? msg-info)            ;; Ignore forwarded messages
    ;; TODO: use polymorphic process-message rather than extracting text here
    (when-let [text (wacomm/message-text msg-info)]
      (let [jid (wacomm/message-chat-jid msg-info)]
        (condp = text
          "/disconnect" (wacomm/disconnect)
          "/test" (wacomm/send-text-message jid "I got your test message.")
          ;; else default message processing
          (process-wa-text-message event-ch jid text))))))

(defn -main [& args]
  )

(comment
  (def dcomm-config (dcomm/init "config.edn"))
  (def evt-ch (:event-ch dcomm-config))
  (def wa-ret (wacomm/init-api (partial handle-wa-message evt-ch)))
  (def evt-loop-p (future (dcomm/start-listening)))         ;; Clojure future
  (def wa-future (wacomm/connect))

  )