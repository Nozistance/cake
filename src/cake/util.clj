(ns cake.util
  (:require [camel-snake-kebab.core :refer [->kebab-case-keyword ->snake_case_string]]
            [cheshire.core :as json]))

(defn ->json [edn]
  (json/generate-string edn {:key-fn ->snake_case_string}))

(defn ->edn [json]
  (json/parse-string json ->kebab-case-keyword))

(defn key-case [m]
  (let [f (fn [[k v]] (if (keyword? k) [(->snake_case_string k) v] [k v]))]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))
