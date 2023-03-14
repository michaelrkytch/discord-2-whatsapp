(ns build
  (:require [clojure.tools.build.api :as b]))

(def build-dir "target")
(def jar-content (str build-dir "/classes"))
(def basis (b/create-basis {:project "deps.edn"}))
(def version "0.0.1")
(def app-name "messagebridge")
(def uber-file-name (format "%s/%s-%s-standalone.jar" build-dir app-name version)) ; path for result uber file

(defn clean [_]
  (b/delete {:path build-dir})
  (println "Removed " build-dir))

(defn uberjar [_]
  (clean nil)

  (b/copy-dir {:src-dirs   ["resources"]                    ; copy resources
               :target-dir jar-content})

  (b/compile-clj {:basis     basis                          ; compile clojure code
                  :src-dirs  ["src"]
                  :class-dir jar-content
                  :java-opts ["--enable-preview"]}          ; Required by WhatsappWeb4J
                 )

  (b/uber {:class-dir jar-content                           ; create uber file
           :uber-file uber-file-name
           :basis     basis
           :main      'discord2whatsapp.server})            ; here we specify the entry point for uberjar

  (println (format "Uber file created: \"%s\"" uber-file-name)))