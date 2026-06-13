(ns cake.log
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import (java.io File)
           (java.time LocalDate)))

(defn- write-line! [^File dir ^String line]
  (try
    (.mkdirs dir)
    (spit (io/file dir (str "cake." (LocalDate/now) ".log"))
          (str line \newline) :append true)
    (catch Throwable t
      (binding [*out* *err*]
        (println "[cake.log] file write failed:" (.getMessage t))))))

(defn- file-appender [dir]
  (let [dir (io/file dir)]
    {:enabled?  true
     :async?    false
     :min-level nil
     :fn        (fn [data] (write-line! dir (force (:output_ data))))}))

(defn setup! [{:keys [dir level]}]
  (let [dir   (or dir "/data/logs")
        level (or (some-> level keyword) :info)]
    (log/merge-config! {:min-level level
                        :appenders {:file (file-appender dir)}})
    (log/info "Logging ready" {:dir dir :min-level level})))
