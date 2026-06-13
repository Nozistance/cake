(ns cake.telegram.api
  (:require [cake.util :refer [->edn ->json key-case]]
            [camel-snake-kebab.core :refer [->snake_case_string]]
            [clj-http.client :as http]
            [clojure.string :as string])
  (:import (java.io File)
           (java.net URI)))

(def ^:dynamic base-url (atom nil))

(def local-files? (atom true))

(def ^:private remote-file-dir
  (or (System/getenv "REMOTE_FILE_DIR") "/opt/files"))

(def file-dir (atom nil))

(defn- opt-parts [options]
  (for [[k v] options]
    {:name (->snake_case_string k) :content (if (coll? v) (->json v) (str v))}))

(defn- remote-rel [^File f]
  (let [base @file-dir]
    (if (and base (.startsWith (.getPath f) (.getPath (File. ^String base))))
      (-> (.toPath (File. ^String base)) (.relativize (.toPath f)) (.toString))
      (.getName f))))

(defn file-ref [f]
  (if (instance? File f)
    (.toASCIIString (URI. "file" "" (str remote-file-dir "/" (remote-rel f)) nil))
    f))

(defn set-webhook [token webhook-url]
  (let [url (str @base-url token "/setWebhook")
        query {:url webhook-url}]
    (http/get url {:as :json :query-params query})))

(defn remove-webhook [token]
  (let [url (str @base-url token "/deleteWebhook")]
    (http/get url {:as :json})))

(defn get-file [token file-id]
  (let [url (str @base-url token "/getFile")
        body {:file-id file-id}
        resp (http/post url {:content-type :json
                             :form-params  (key-case body)})]
    (-> resp :body ->edn)))

(defn get-user-profile-photos
  ([token user-id] (get-user-profile-photos token user-id {}))
  ([token user-id options]
   (let [url (str @base-url token "/getUserProfilePhotos")
         body (into {:user-id user-id} options)
         resp (http/post url {:content-type :json
                              :form-params  (key-case body)})]
     (-> resp :body ->edn))))

(defn send-text
  ([token chat-id text] (send-text token chat-id {} text))
  ([token chat-id options text]
   (let [url (str @base-url token "/sendMessage")
         body (into {:chat-id chat-id :text text} options)
         resp (http/post url {:content-type :json
                              :body         (->json body)})]
     (-> resp :body ->edn))))

(defn forward-message
  ([token chat-id from-chat-id message-id]
   (forward-message token chat-id from-chat-id message-id {}))
  ([token chat-id from-chat-id message-id options]
   (let [url (str @base-url token "/forwardMessage")
         body (into {:chat-id      chat-id
                     :from-chat-id from-chat-id
                     :message-id   message-id} options)
         resp (http/post url {:content-type :json
                              :form-params  (key-case body)})]
     (-> resp :body ->edn))))

(defn edit-text
  ([token chat-id message-id text] (edit-text token chat-id message-id {} text))
  ([token chat-id message-id options text]
   (let [url (str @base-url token "/editMessageText")
         query (into {:chat-id chat-id :text text :message-id message-id} options)
         resp (http/post url {:content-type :json
                              :form-params  (key-case query)})]
     (-> resp :body ->edn))))

(defn delete-text [token chat-id message-id]
  (let [url (str @base-url token "/deleteMessage")
        body {:chat-id chat-id :message-id message-id}
        resp (http/post url {:content-type :json
                             :form-params  (key-case body)})]
    (-> resp :body ->edn)))

(defn send-file [token chat-id options file method field _filename]
  (let [url (str @base-url token method)]
    (if (and (instance? File file) (not @local-files?))
      (-> (http/post url {:multipart (into [{:name "chat_id" :content (str chat-id)}
                                            {:name field :content ^File file}]
                                           (opt-parts options))})
          :body ->edn)
      (-> (http/post url {:content-type :json
                          :body (->json (into {:chat-id chat-id (keyword field) (file-ref file)} options))})
          :body ->edn))))

(defn is-file? [value] (= File (type value)))

(defn of-type? [^File file valid-extensions]
  (some #(-> file .getName (.endsWith %))
        valid-extensions))

(defn assert-file-type [value valid-extensions]
  (when (and (is-file? value)
             (not (of-type? value valid-extensions)))
    (throw (ex-info (str "Telegram API only supports the following formats: "
                         (string/join ", " valid-extensions)
                         " for this method. Other formats may be sent using send-document")
                    {}))))

(defn send-photo
  ([token chat-id image] (send-photo token chat-id {} image))
  ([token chat-id options image]
   (assert-file-type image ["jpg" "jpeg" "gif" "png" "tif" "bmp"])
   (send-file token chat-id options image "/sendPhoto" "photo" "photo.png")))

(defn send-document
  ([token chat-id document] (send-document token chat-id {} document))
  ([token chat-id options document]
   (send-file token chat-id options document "/sendDocument" "document" "document")))

(defn send-video
  ([token chat-id video] (send-video token chat-id {} video))
  ([token chat-id options video]
   (when (is-file? video) (assert-file-type video ["mp4"]))
   (send-file token chat-id (assoc options :supports-streaming true)
              video "/sendVideo" "video" "video.mp4")))

(defn send-audio
  ([token chat-id audio] (send-audio token chat-id {} audio nil))
  ([token chat-id options audio] (send-audio token chat-id options audio nil))
  ([token chat-id options audio thumbnail]
   (when (is-file? audio) (assert-file-type audio ["mp3" "m4a"]))
   (let [url (str @base-url token "/sendAudio")]
     (if (and (instance? File audio) (not @local-files?))
       (let [parts (cond-> (into [{:name "chat_id" :content (str chat-id)}
                                  {:name "audio" :content ^File audio}]
                                 (opt-parts options))
                     (instance? File thumbnail)
                     (conj {:name "thumbnail" :content "attach://thumb"}
                           {:name "thumb" :content ^File thumbnail}))]
         (-> (http/post url {:multipart parts}) :body ->edn))
       (let [body (cond-> (into {:chat-id chat-id :audio (file-ref audio)} options)
                    thumbnail (assoc :thumbnail (file-ref thumbnail)))]
         (-> (http/post url {:content-type :json :body (->json body)}) :body ->edn))))))

(defn send-video-id
  ([token chat-id file-id] (send-video-id token chat-id {} file-id))
  ([token chat-id options file-id]
   (let [url (str @base-url token "/sendVideo")
         body (into {:chat-id chat-id :video file-id} options)]
     (-> (http/post url {:content-type :json :body (->json body)}) :body ->edn))))

(defn send-audio-id
  ([token chat-id file-id] (send-audio-id token chat-id {} file-id))
  ([token chat-id options file-id]
   (let [url (str @base-url token "/sendAudio")
         body (into {:chat-id chat-id :audio file-id} options)]
     (-> (http/post url {:content-type :json :body (->json body)}) :body ->edn))))

(defn send-sticker
  ([token chat-id sticker] (send-sticker token chat-id {} sticker))
  ([token chat-id options sticker]
   (assert-file-type sticker ["webp"])
   (send-file token chat-id options sticker "/sendSticker" "sticker" "sticker.webp")))

(defn answer-inline
  ([token inline-query-id results] (answer-inline token inline-query-id {} results))
  ([token inline-query-id options results]
   (let [url (str @base-url token "/answerInlineQuery")
         body (into {:inline-query-id inline-query-id :results results} options)
         resp (http/post url {:content-type :json
                              :form-params  (key-case body)})]
     (-> resp :body ->edn))))

(defn answer-callback
  ([token callback-query-id] (answer-callback token callback-query-id "" false))
  ([token callback-query-id text] (answer-callback token callback-query-id text false))
  ([token callback-query-id text show-alert]
   (let [url (str @base-url token "/answerCallbackQuery")
         body {:callback-query-id callback-query-id :text text :show-alert show-alert}
         resp (http/post url {:content-type :json
                              :form-params  (key-case body)})]
     (-> resp :body ->edn))))

(defn send-chat-action
  ([token chat-id action] (send-chat-action token chat-id {} action))
  ([token chat-id options action]
   (let [url (str @base-url token "/sendChatAction")
         body (into {:chat-id chat-id :action (->snake_case_string action)} options)
         resp (http/post url {:content-type :json
                              :form-params  (key-case body)})]
     (-> resp :body ->edn))))
