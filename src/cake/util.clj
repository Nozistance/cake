(ns cake.util
  (:require [camel-snake-kebab.core :refer [->kebab-case-keyword ->snake_case_string]]
            [cheshire.core :as json]))

(defn ->json [edn]
  (json/generate-string edn {:key-fn ->snake_case_string}))

(defn ->edn [json]
  (json/parse-string json ->kebab-case-keyword))
