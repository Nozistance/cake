(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file  "target/cake.jar")
(def basis     (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "config" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis      basis
                  :src-dirs   ["src"]
                  :class-dir  class-dir
                  :ns-compile '[cake.system]})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis     basis
           :main      'cake.system}))
