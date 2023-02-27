(ns discord2whatsapp.store
  "File-backed atom for storing app data.
  Initialized as a bimap, packaged as a component."
  (:require [bimap]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log])
  (:import (java.io PushbackReader)))

(defn- load-from-file
  "Load edn data from the given file.  If file does not exist or cannot be read,
  will create, store and return a new app store bimap structure."
  [path]
  (let [data (try
               (when-not (.exists (io/file path))
                 (io/make-parents path)
                 (spit path {}))

               (with-open [r (io/reader path)]
                 (edn/read (PushbackReader. r)))
               (catch Exception e
                 (log/warnf "Unable to load file '%s': %s\n" path (.getMessage e))))]
    (bimap/->BiMap data)))

(defn- init-store
  "Returns an atom for storing state that acts as a write-through cache to the given file path,
  loading its initial contents from path."
  [path]
  (let [watch-fn (fn [_ _ _ data]
                   (spit path data))
        store (atom (load-from-file path))]
    (add-watch store :write-through-watcher watch-fn)
    store))

(defrecord Store [path data]
  component/Lifecycle
  (start  [this]
    (assoc this :data (init-store path)))
  (stop [this]
    (assoc this :data nil)))

(defn new-store
  "Store component constructor"
  [path]
  (map->Store {:path path}))

(defn data
  "Return the store's data"
  [store]
  @(:data store))

(defn assoc-kv!
  "Assoc the kv pair into the store"
  [store k v]
  (swap! (:data store) assoc k v))