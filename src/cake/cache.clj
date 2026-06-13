(ns cake.cache
  (:require [clojure.core.cache.wrapped :as cw]))

(defn ttl-store [ttl-ms] (cw/ttl-cache-factory {} :ttl ttl-ms))

(defn put! [store k v] (cw/miss store k v) nil)

(defn take! [store k]
  (when (cw/has? store k)
    (let [v (cw/lookup store k)] (cw/evict store k) v)))
