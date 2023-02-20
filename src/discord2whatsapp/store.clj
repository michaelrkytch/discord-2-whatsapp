(ns discord2whatsapp.store
  (:require [bimap]))

(defonce ^:private store (atom {:chat-map (bimap/->BiMap [])}))

(defn chat-mapping
  "Return the channel-chat bimap"
  []
  (:chat-map @store))
(defn store-mapping!
  "Store channel-chat mapping."
  [channel-id chat-id]
  (swap! store update :chat-map assoc channel-id chat-id))
