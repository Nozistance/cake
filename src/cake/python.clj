(ns cake.python
  (:require [cake.util :refer [->json]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)
           (java.util.function BiConsumer)))

(def ^:private download-lock (Object.))

(def ^:private hard-timeout-min 60)

(defn script-file ^String []
  (let [tmp (File/createTempFile "cake-ytdlp" ".py")]
    (.deleteOnExit tmp)
    (with-open [in (io/input-stream (io/resource "cake/ytdlp.py"))]
      (io/copy in tmp))
    (.getPath tmp)))

(defn- pump-stderr! [^Process p ^BiConsumer cb]
  (let [buf (atom [])
        t (Thread.
            #(with-open [r (io/reader (.getErrorStream p))]
               (doseq [line (line-seq r)]
                 (if (str/starts-with? line "PROGRESS ")
                   (when cb
                     (let [[_ g t] (str/split line #"\s+")]
                       (try (.accept cb (Long/parseLong g) (Long/parseLong t))
                            (catch Exception _ nil))))
                   (swap! buf conj line)))))]
    (.setName t "ytdlp-stderr")
    (.setDaemon t true)
    (.start t)
    [t buf]))

(defn- run [{:keys [python-bin script]} req-map ^BiConsumer cb]
  (let [p       (-> (ProcessBuilder. (into-array String [python-bin script]))
                    (.start))
        [_ buf] (pump-stderr! p cb)
        out-fut (future (slurp (.getInputStream p) :encoding "UTF-8"))]
    (with-open [os (.getOutputStream p)]
      (.write os (.getBytes ^String (->json req-map) "UTF-8")))
    (if (.waitFor p (long hard-timeout-min) TimeUnit/MINUTES)
      (let [code (.exitValue p)
            out  (str/trim @out-fut)]
        (when-not (zero? code)
          (throw (ex-info (str "yt-dlp exited " code ": "
                               (str/join " | " (take-last 20 @buf)))
                          {:code code})))
        out)
      (do (.destroyForcibly p)
          (future-cancel out-fut)
          (throw (ex-info (str "yt-dlp timed out after " hard-timeout-min "m")
                          {:timeout-min hard-timeout-min}))))))

(defn download! [runner url fmt outtmpl cb]
  (locking download-lock
    (run runner {:cmd "download" :url url :fmt fmt :outtmpl outtmpl} cb)))

(defn info! [runner url]
  (run runner {:cmd "info" :url url} nil))

(defn sizes [runner info-json selectors-json]
  (run runner {:cmd "sizes" :info info-json :selectors selectors-json} nil))

(defn download-info! [runner info-json fmt outtmpl cb]
  (locking download-lock
    (run runner {:cmd "download_info" :info info-json :fmt fmt :outtmpl outtmpl} cb)))

(defn audio-info! [runner info-json outtmpl cb]
  (locking download-lock
    (run runner {:cmd "audio_info" :info info-json :outtmpl outtmpl} cb)))
