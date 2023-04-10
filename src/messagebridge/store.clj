(ns messagebridge.store
  "File-backed atom for storing app data.
  Initialized as a bimap, packaged as a component."
  (:require [bimap]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component])
  (:import (java.io PushbackReader)
           (java.nio.file Files LinkOption Path StandardOpenOption)))
(def empty-link-options (make-array LinkOption 0))

(defn write-file
  "Replace contents of the file at Path with data"
  [^Path path data]
  (with-open [os (Files/newOutputStream path
                                        (into-array [StandardOpenOption/CREATE
                                                     StandardOpenOption/WRITE]))]
    (spit os data)))

(defn read-file
  "Read edn data from file at Path"
  [^Path path]
  (with-open [r (Files/newBufferedReader path)]
    (edn/read (PushbackReader. r))))


(defn- load-file
  "Load edn data from the given file.  If file does not exist or cannot be read,
  will create, store and return a new app store bimap structure.
  path is a java.nio.file.Path"
  [^Path path]
  (let [data (try
               ;; Create empty config file if necessary
               (when-not (Files/exists path empty-link-options)
                 (write-file path {}))
               ;; Read config
               (read-file path)
               (catch Exception e
                 (log/warnf e "Unable to load file '%s': %s\n" path (.getMessage e))))]
    (bimap/->BiMap data)))

(defn- init-store
  "Returns an atom for storing state that acts as a write-through cache to the given file path,
  loading its initial contents from path.
  path is a java.nio.file.Path"
  [^Path path]
  (let [watch-fn (fn [_ _ _ data]
                   (write-file path data))
        store (atom (load-file path))]
    (add-watch store :write-through-watcher watch-fn)
    store))

(defrecord Store [^Path path data]
  component/Lifecycle
  (start  [this]
    (assoc this :data (init-store path)))
  (stop [this]
    (assoc this :data nil)))

(defn new-store
  "Store component constructor
  path is a java.nio.file.Path"
  [^Path path]
  (map->Store {:path path}))

(defn data
  "Return the store's data"
  [store]
  @(:data store))

(defn assoc-kv!
  "Assoc the kv pair into the store"
  [store k v]
  (swap! (:data store) assoc k v))