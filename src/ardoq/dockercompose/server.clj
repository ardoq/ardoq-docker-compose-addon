(ns ardoq.dockercompose.server
  (:require
            [org.httpkit.server :refer [run-server]]
            [com.stuartsierra.component :as component]
            [ardoq.dockercompose.api :refer [app]])
  (:gen-class))

(defn- start-server [handler port]
  (let [server (run-server handler {:port port})]
    (println (str "Started server on port:" port))
    server))

(defn- stop-server [server]
  (when server
    (server)))                                              ;; run server returns fn that stops itself

(defrecord DockerComposeAddon []
  component/Lifecycle
  (start [this]
    (assoc this :server (start-server #'app 80)))
  (stop [this]
    (stop-server (:server this))
    (dissoc this :server)))

(defn create-system []
  (DockerComposeAddon.))

(defn -main [& args]
  (.start (create-system)))

