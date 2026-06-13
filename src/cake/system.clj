(ns cake.system
  (:require [aero.core :as aero]
            [aleph.http :as aleph]
            [cake.cache :as cache]
            [cake.core :as core]
            [cake.log :as clog]
            [cake.python :as py]
            [cake.telegram.api :as t]
            [cake.telegram.handlers :as h]
            [cake.util :refer [->edn]]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import (java.util.concurrent ExecutorService Executors)))

(defn load-context []
  (aero/read-config
    (or (System/getenv "CONFIG") "config/parameters.edn")
    {:profile (keyword (or (System/getenv "PROFILE") "prod"))}))

(defn system [cfg]
  {:cake/cache     {:ttl-min 15}
   :cake/python    {:python-bin (:python-bin cfg)}
   :cake/worker    {}
   :cake/handler   {:config cfg
                    :python (ig/ref :cake/python)
                    :cache  (ig/ref :cake/cache)}
   :cake/server    {:port    (:port cfg)
                    :handler (ig/ref :cake/handler)
                    :worker  (ig/ref :cake/worker)}
   :cake/telegram  {:token       (:token cfg)
                    :base-url    (:base-url cfg)
                    :webhook     (:webhook cfg)
                    :local-files (:local-files cfg)
                    :file-dir    (:file-dir cfg)
                    :server      (ig/ref :cake/server)}})

(defmethod ig/init-key :cake/cache [_ {:keys [ttl-min]}]
  {:yt-info (cache/ttl-store (* ttl-min 60 1000))})

(defmethod ig/init-key :cake/python [_ {:keys [python-bin]}]
  (let [script (py/script-file)]
    (log/info "yt-dlp runner ready" {:python python-bin :script script})
    {:dl {:python-bin python-bin :script script}}))

(defmethod ig/init-key :cake/worker [_ _] (Executors/newCachedThreadPool))

(defmethod ig/halt-key! :cake/worker [_ ^ExecutorService w] (.shutdownNow w))

(defmethod ig/init-key :cake/handler [_ {:keys [config python cache]}]
  (let [ctx (assoc config :python python :cache cache)]
    (h/handlers
      (h/command-fn "start" (partial #'core/start-command-fn ctx))
      (h/callback-fn (partial #'core/callback-fn ctx))
      (h/message-fn (partial #'core/message-fn ctx)))))

(defn- proceed [^ExecutorService worker dispatch request]
  (when-let [body (some-> request :body slurp ->edn)]
    (log/info "Update" {:id (:update-id body)})
    (log/debug "Update body" body)
    (.execute worker #(try (dispatch body)
                           (catch Throwable t (log/error "Dispatch crashed" t))))
    {:headers {"content-type" "text/plain"} :status 200}))

(defmethod ig/init-key :cake/server [_ {:keys [port handler worker]}]
  (log/info "Starting server" {:port port})
  (aleph/start-server (partial proceed worker handler) {:port port}))

(defmethod ig/halt-key! :cake/server [_ server] (.close server))

(defmethod ig/init-key :cake/telegram [_ {:keys [token base-url webhook local-files file-dir]}]
  (reset! t/base-url base-url)
  (reset! t/local-files? (boolean local-files))
  (reset! t/file-dir file-dir)
  (log/info "Setting webhook" {:webhook webhook :tg-api base-url :local-files (boolean local-files)})
  (try
    (t/set-webhook token webhook)
    (catch Exception e
      (log/warn "Webhook not set, continuing" {:webhook webhook :error (.getMessage e)})))
  {:token token :webhook webhook})

(defn -main [& _]
  (let [cfg (load-context)]
    (clog/setup! {:dir (:log-dir cfg) :level (:log-level cfg)})
    (log/info "Starting cake" {:profile (or (System/getenv "PROFILE") "prod")})
    (log/info "Clearing leftover files" {:dir (:file-dir cfg)})
    (core/clear-files! (:file-dir cfg))
    (let [sys (ig/init (system cfg))]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(do (log/info "Shutting down") (ig/halt! sys))))
      @(promise))))

(comment
  (require '[integrant.repl :as ir])
  (ir/set-prep! #(system (load-context)))
  (ir/go)
  (ir/reset)
  (ir/halt))
