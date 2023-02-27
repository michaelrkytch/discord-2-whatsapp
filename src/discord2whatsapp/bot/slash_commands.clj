(ns discord2whatsapp.bot.slash-commands
  (:require
    [discljord.messaging :as m]
    [discord2whatsapp.store :as store]
    [discord2whatsapp.wacomms :as wacomm]
    [slash.command.structure :as slash]
    [slash.response :as resp]
    [clojure.tools.logging :as log]))

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
          (log/debug "Registering " name)
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

(defn handle-wa-connect [store channel-id chat-jid]
  (if (store/assoc-kv! store channel-id chat-jid)
    (interaction-response (str "Connected channel " channel-id " to chat " chat-jid) :ephemeral true)
    ;; else store failure
    (interaction-response (str "Unable to store mapping to chat " chat-jid) :ephemeral true)))

(defn- chat-list-filter
  "Default chat list filter -- include chats that have a 'createdBy' and are not archived."
  [chat]
  (and (.. chat (createdBy) (isPresent))
       (not (.archived chat))))

(defn list-chats
  "Returns a response containing the names of all chats that meet the specified criteria, or nil if no matches.
   Filter optionally passed as :filter-fn, defaults to `chat-list-filter`"
  [wacomms filter-fn]
  (let [max-response-chars 2000
        chats (wacomm/list-chats wacomms filter-fn)
        chat-display (map #(str (.jid %) "\t(" (.name %) ")") chats)]
    (when (seq chat-display)
      (->> chat-display
           (reduce #(str %1 "\n" %2))
           ;; truncate if necessary
           (#(if (> (count %) max-response-chars)
               (subs % 0 max-response-chars)
               %))
           interaction-response))))

(defn handle-wa-list-chats
  [wacomms & {filter-fn :filter :or {filter-fn chat-list-filter}}]
  (or
    (list-chats wacomms filter-fn)
    (interaction-response "No known WhatsApp chats for user.")))

(defn handle-wa-search-chats
  "Returns a response containing the names of all chats whose names contain the given substring"
  [wacomms sstr]
  (letfn [(filter-fn [chat]
            (let [name (.. chat (name) (toLowerCase))]
              (.contains name (.toLowerCase sstr))))]
    (or
      (list-chats wacomms filter-fn)
      (interaction-response (str "No chats matching '" sstr "'")))))

(defn process-interaction-create
  "Transform the interaction data into response data"
  [_
   {:keys                       [type guild-id channel-id]
    {command-name     :name
     [{value :value}] :options} :data
    :as                         interaction}
   wacomms
   store
   ]
  (log/debug "Received interaction " command-name)
  ;;(clojure.pprint/pprint interaction)
  (try
    (condp = command-name
      "wa-connect" (handle-wa-connect store channel-id value)
      "wa-list-chats" (handle-wa-list-chats wacomms)
      "wa-search-chats" (handle-wa-search-chats wacomms value)
      ;; else
      (interaction-response (str "I don't know the command '" command-name \') :ephemeral true))
    (catch Exception e
      (log/warn "Exception processing command: " e)
      (interaction-response (str "Error processing command.") :ephemeral true))))