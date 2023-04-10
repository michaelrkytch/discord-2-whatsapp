(ns messagebridge.store.gcs-store
  "Expose Google Cloud Storage bucket as java.nio.file.Filesystem"
  (:import (java.net URI)
           (java.nio.file FileSystems)))

(def empty-string-array (make-array String 0))

(defn get-bucket-fs [bucket-name]
  (let [uri (URI/create (str "gs://" bucket-name))]
    (FileSystems/getFileSystem uri)))

(defn get-path [fs path]
  (.getPath fs path empty-string-array))

(defn get-root-dir [fs]
  (get-path fs "/"))


