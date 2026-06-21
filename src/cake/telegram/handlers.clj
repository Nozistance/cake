(ns cake.telegram.handlers
  (:require [clojure.string :as s]))

(defn handling [request & handlers]
  (some #(% request) handlers))

(defn handlers [& handlers]
  #(apply handling % handlers))

(defn command? [update name]
  (some-> update
          :message
          :text
          (s/split #"\s+")
          (first)
          (s/split #"@")
          (first)
          (= (str "/" name))))

(defn command-fn [name handler]
  (fn [update]
    (if (command? update name)
      (handler (:message update)))))

(defn update-fn [path handler-fn]
  (fn [update]
    (if-let [data (get-in update path)]
      (handler-fn data))))

(defn message-fn [handler-fn]
  (update-fn [:message] handler-fn))

(defn callback-fn [handler-fn]
  (update-fn [:callback-query] handler-fn))
