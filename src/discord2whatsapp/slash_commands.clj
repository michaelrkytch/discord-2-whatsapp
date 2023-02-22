(ns discord2whatsapp.slash-commands
  (:require
    [discljord.messaging :as m]
    [slash.command.structure :as slash]
    [slash.response :as resp]
    [discord2whatsapp.wa-comms :as wacomm]
    [discord2whatsapp.store :as store]))

;; -------------------------------
;; Slash Commands
;; -------------------------------

(def wa-connect-command
  (slash/command "wa-connect"
                 "Connect a WhatsApp chat to this channel"
                 :options [(slash/option "chat-id" "WhatsApp chat jid" :string :required true)]))

(def wa-list-chats-command
  (slash/command "wa-list-chats" "List all chats for this user"))

(def wa-search-chats-command
  (slash/command "wa-search-chats"
                 "Search for user's chats matching the given string"
                 :options [(slash/option "name-contains" "Search string" :string :required true)]))

(def slash-commands [wa-connect-command wa-list-chats-command wa-search-chats-command])

(defn register-wa-commands! [app-id api-ch guild-id commands & {force-reg :force :or {force-reg false}}]
  (let [registered-commands (->> (m/get-guild-application-commands! api-ch app-id guild-id)
                                 deref
                                 (map :name))]
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

;; -------------------------------
;; Command interactions
;; -------------------------------

(defn interaction-response
  "Create an interaction response.  Pass :ephemeral true for ephemeral message."
  [text & {ephemeral :ephemeral}]
  (let [msg (resp/channel-message {:content text})]
    (if ephemeral
      (resp/ephemeral msg)
      msg)))

(defn handle-wa-connect [channel-id chat-jid]
  (if (wacomm/chat-by-jid chat-jid)
    (if (store/store-mapping! channel-id chat-jid)
      (interaction-response (str "Connected channel " channel-id " to chat " chat-jid) :ephemeral true)
      ;; else store failure
      (interaction-response (str "Unable to store mapping to chat " chat-jid) :ephemeral true))
    ;; else couldn't find chat
    (interaction-response (str "Unknown chat jid " chat-jid) :ephemeral true)
    ))

(defn- chat-list-filter
  "Default chat list filter -- include chats that have a 'createdBy' and are not archived."
  [chat]
  (and (.. chat (createdBy) (isPresent))
       (not (.archived chat))))

(defn handle-wa-list-chats
  "Returns a response containing the names of all chats that meet the specified criteria.
   Filter optionally passed as :filter-fn, defaults to `chat-list-filter`"
  [& {filter-fn :filter :or {filter-fn chat-list-filter}}]
  (let [max-response-chars 2000
        chats (wacomm/list-chats filter-fn)
        chat-display (map #(str (.jid %) "\t(" (.name %) ")") chats)]
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

(defn handle-wa-search-chats
  "Returns a response containing the names of all chats whose names contain the given substring"
  [str]
  (letfn [(filter-fn [chat]
            (let [name (.. chat (name) (toLowerCase))]
              (.contains name (.toLowerCase str))))]
    (handle-wa-list-chats :filter filter-fn)))

(defn process-interaction-create
  "Transform the interaction data into response data"
  [_
   {:keys                       [type guild-id channel-id]
    {command-name     :name
     [{value :value}] :options} :data
    :as                         interaction}
   ]
  (println "Received interaction " command-name)
  ;;(clojure.pprint/pprint interaction)
  (try
    (condp = command-name
      "wa-connect" (handle-wa-connect channel-id value)
      "wa-list-chats" (handle-wa-list-chats)
      "wa-search-chats" (handle-wa-search-chats value)
      ;; else
      (interaction-response (str "I don't know the command '" command-name \') :ephemeral true))
    (catch Exception e
      ;; TODO log
      (interaction-response (str "Error processing command\n" e) :ephemeral true))))