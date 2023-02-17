(ns discord2whatsapp.store
  (:require [bimap]))

(def ^:private store (atom {:chat-map (bimap/->BiMap [])}))

(defn chat-mapping
  "Return the channel-chat bimap"
  []
  (:chat-map @store))
(defn store-mapping
  "Store channel-chat mapping."
  [channel-id chat-name]
  (swap! store update :chat-map assoc channel-id chat-name))
