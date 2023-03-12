(ns bimap
  (:require [potemkin]))


;Use potemkin/def-map-type to create the type with all the required map methods.
;All standard map functions behave exactly like a normal “forward” map.
;`backward-view` will return the backward map.
;In addition `get-backward` will do the reverse lookup, using the backward map.
(potemkin/def-map-type BiMap [forward backward mta]
  (get [this key default-value]
       (get forward key default-value))
  (assoc [this key value]
    (BiMap.
      (assoc forward key value)
      (assoc backward value key)
      mta))
  (dissoc [this key]
    (BiMap.
      (dissoc forward key)
      (dissoc backward (get forward key))
      mta))
  (keys [this]
    (keys forward))
  (meta [this]
    mta)
  (with-meta [this new-meta]
    (BiMap. forward backward new-meta))
  )

(defn ->BiMap
  "Create BiMap
  (seq data) must yield a sequence of kv pairs."
  [data]
  (if-let [pairs (seq data)]
    (let [forward (into {} pairs)
          backward (->> pairs
                        (map (comp vec reverse))
                        (into {}))]
      (BiMap. forward backward nil))
    ;; else create empty BiMap
    (BiMap. {} {} nil)))

(defn backward-map [bimap]
  (.backward bimap))

(defn get-backward [bimap k]
  (get (.backward bimap) k))