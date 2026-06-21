(ns cake.core
  (:require [cake.cache :as cache]
            [cake.python :as py]
            [cake.telegram.api :as t]
            [cake.util :refer [->edn ->json]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import (java.io File)
           (java.net URI)
           (java.util.function BiConsumer)))

(defn clear-files! [dir]
  (when-let [^File d (some-> dir io/file)]
    (.mkdirs d)
    (doseq [^File f (.listFiles d)]
      (if (.isDirectory f)
        (run! #(try (.delete ^File %) (catch Exception _ nil)) (reverse (file-seq f)))
        (try (.delete f) (catch Exception _ nil))))))

(defn- req-dir ^File [file-dir]
  (doto (io/file file-dir (str (System/nanoTime))) (.mkdirs)))

(defn- delete-dir! [^File dir]
  (run! #(try (.delete ^File %) (catch Exception _ nil)) (reverse (file-seq dir))))

(defn- mb [b] (/ (double b) 1048576.0))

(defn- progress-reporter ^BiConsumer [token chat-id message-id]
  (let [state (atom {:t 0 :pct -2})]
    (reify BiConsumer
      (accept [_ got total]
        (let [got (long got) total (long total)
              now (System/currentTimeMillis)
              pct (if (pos? total) (quot (* 100 got) total) -1)]
          (when (and (>= (- now (:t @state)) 2500) (not= pct (:pct @state)))
            (reset! state {:t now :pct pct})
            (try
              (t/edit-text token chat-id message-id
                           (if (pos? total)
                             (format "Погоди, качаю…   %d%% (%.1f / %.1f MB)" pct (mb got) (mb total))
                             "Погоди, качаю…"))
              (catch Exception _ nil))))))))

(defn classify-uri [uri]
  (try
    (let [host (str/lower-case (.getHost (URI. ^String uri)))]
      (cond
        (or (str/includes? host "youtube.com")
            (str/includes? host "youtu.be"))  :youtube
        (str/includes? host "instagram")      :instagram
        :else                                 :generic))
    (catch Exception _ nil)))

(defn options [{{chat-id :id} :chat :keys [message-id text]}]
  {:disable-notification true
   :reply-parameters     {:quote-parse-mode "HTML" :chat-id chat-id
                          :message-id       message-id :quote text}})

(defn- keep-action! [token chat-id action body-fn]
  (let [stop (atom false)
        th (doto (Thread.
                   #(while (not @stop)
                      (try (t/send-chat-action token chat-id action) (catch Exception _ nil))
                      (try (^[long] Thread/sleep 1000) (catch InterruptedException _ nil))))
             (.setDaemon true) (.setName "chat-action") (.start))]
    (try (body-fn)
         (finally (reset! stop true) (.interrupt th)))))

(defmulti handle-uri (fn [_ctx uri-type _message] uri-type))

(defn handle-uri-youtube [ctx _uri-type {{chat-id :id} :chat :keys [text]}]
  (let [{:keys [token edit-id quality quality-dict file-limit-kb python cache]} ctx
        code (str (System/nanoTime))
        [info sizes] (let [i (py/info! (:dl python) text)]
                       [i (->edn (py/sizes (:dl python) i (->json quality)))])
        buttons (for [[q size] sizes :when (and (pos? size) (< size (* file-limit-kb 1024)))]
                  [{:text          (format (quality-dict q) (/ size 1024.0 1024.0))
                    :callback-data (->json {:quality q :code code :edit-id edit-id})}])]
    (log/info "sizes result" {:count (count buttons) :sizes (map (fn [[q s]] [q s]) sizes)})
    (cache/put! (:yt-info cache) code info)
    (t/edit-text token chat-id edit-id {:reply-markup {:inline-keyboard buttons}}
                 "Выбери качество видоса :O\n\n*Для музыки или подкаста жми «Только аудио»")))

(defmethod handle-uri :youtube [ctx uri-type message]
  (#'handle-uri-youtube ctx uri-type message))

(defmethod handle-uri :generic [ctx _ {{chat-id :id} :chat :keys [text] :as message}]
  (let [{:keys [token file-dir python edit-id]} ctx
        dir     (req-dir file-dir)
        outtmpl (str (.getPath dir) "/%(id)s.%(ext)s")
        cb      (progress-reporter token chat-id edit-id)]
    (try
      (let [path (py/download! (:dl python) text
                               "bv*[vcodec^=avc1]+ba[ext=m4a]/bv*[ext=mp4]+ba/b[ext=mp4]/bv*+ba/b"
                               outtmpl cb)
            file (io/file path)]
        (log/info "downloaded" {:path path :exists (.exists file) :size (.length file)})
        (t/delete-text token chat-id edit-id)
        (let [resp (keep-action! token chat-id :upload-video
                                 #(t/send-video token chat-id (options message) file))]
          (log/info "send-video response" {:ok (:ok resp)})))
      (finally (delete-dir! dir)))))

(defn start-command-fn [{:keys [token]} {{chat-id :id} :chat}]
  (->> ["Бот для скачивания коротких глупеньких видосов с"
        "YT (Shorts), TikTok, Instagram Reels, Facebook, etc."
        "Просто кинь сюда ссылку, чтобы получить видос 🙌"]
       (str/join \newline) (t/send-text token chat-id)))

(defn- notify-fail!
  ([token chat-id edit-id e] (notify-fail! token chat-id edit-id {} e))
  ([token chat-id edit-id options e]
   (log/error "Download failed" e)
   (let [msg "Сорян, не вышло :("]
     (try (t/edit-text token chat-id edit-id options msg)
          (catch Exception _
            (try (t/send-text token chat-id options msg) (catch Exception _ nil)))))))

(defn message-fn [ctx {{chat-id :id} :chat :keys [message-id text from] :as message}]
  (let [token (:token ctx) options (options message)]
    (if-let [uri-type (classify-uri text)]
      (do
        (log/info "Handling" {:id message-id :uri text :uri-type uri-type :from from})
        (when (= uri-type :instagram)
          (t/send-text token chat-id options
                       "С инстой не всегда выходит. Иногда там возрастные ограничения, иногда — приватный аккаунт. Просто предупреждаю"))
        (let [{{edit-id :message-id} :result} (t/send-text token chat-id options "Погодь, ищу видос...")]
          (try
            (handle-uri (assoc ctx :edit-id edit-id)
                        (if (= uri-type :instagram) :generic uri-type) message)
            (catch Exception e
              (notify-fail! token chat-id edit-id options e)))))
      (t/send-text token chat-id options "Странная ссылка какая-то. Давай другую"))))

(defn callback-fn [ctx {id :id {chat-id :id} :from :as inline}]
  (let [{:keys [token file-dir python cache] selectors :quality} ctx
        {:keys [code edit-id] q :quality} (->edn (:data inline))
        info-json (cache/take! (:yt-info cache) code)]
    (t/answer-callback token id)
    (if (nil? info-json)
      (do (t/delete-text token chat-id edit-id)
          (t/send-text token chat-id "Кинь ссылку заново, я потерял 0_o"))
      (let [cb  (progress-reporter token chat-id edit-id)
            dir (req-dir file-dir)]
        (try
          (t/edit-text token chat-id edit-id "Погоди, качаю…")
          (if (= q "audio")
            (let [{:keys [audio thumb]} (->edn (py/audio-info! (:dl python) info-json
                                                               (str (.getPath dir) "/%(title)s.%(ext)s") cb))
                  info (->edn info-json)
                  af   (io/file audio)
                  tf   (some-> thumb io/file)
                  opts (cond-> {}
                         (:duration info) (assoc :duration (long (:duration info)))
                         (or (:track info) (:title info)) (assoc :title (or (:track info) (:title info)))
                         (or (:artist info) (:uploader info) (:channel info))
                         (assoc :performer (or (:artist info) (:uploader info) (:channel info))))]
              (t/delete-text token chat-id edit-id)
              (keep-action! token chat-id :upload-voice
                            #(t/send-audio token chat-id opts af tf)))
            (let [vf (io/file (py/download-info! (:dl python) info-json (selectors (keyword q))
                                                 (str (.getPath dir) "/%(id)s.%(ext)s") cb))]
              (t/delete-text token chat-id edit-id)
              (keep-action! token chat-id :upload-video
                            #(t/send-video token chat-id vf))))
          (catch Exception e
            (notify-fail! token chat-id edit-id e))
          (finally (delete-dir! dir)))))))
