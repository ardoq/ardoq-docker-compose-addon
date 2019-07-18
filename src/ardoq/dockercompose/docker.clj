(ns ardoq.dockercompose.docker
  (:require
   [cheshire.core :refer [generate-string parse-string]]
   [clojure.set :refer [union map-invert]]
   [org.httpkit.client :as http]))

(defn- v2-authorize [account password repository]
  (let [credentials [account password]
        service "registry.docker.io"
        scope (str "repository:" repository ":pull")
        auth-options {:timeout 2000
                      :query-params {:service service, :scope scope, :account account}
                      :basic-auth credentials}
        {:keys [status headers body error] :as resp} @(http/get "https://auth.docker.io/token" auth-options)]

    (if error
      (println "Failed, exception: " error)
      (:token (parse-string body true)))))

(defn- p-v2-image-ancestry [auth-token image-hash]
  (if (empty? image-hash)
    (do (prn "Empty image id - continuing...") []))
  (let [req-options {:timeout 2000
                     :headers {"Authorization" (str "Bearer " auth-token)}}
        {:keys [status headers body error] :as resp} @(http/get (str "https://registry-1.docker.io/v2/images/" image-hash "/ancestry") req-options)]
    (if error
      (throw (RuntimeException. error))
      (parse-string body true))))

(defn- p-v2-list-tags [auth-token repository]
  (let [req-options {:timeout 2000
                     :headers {"Authorization" (str "Bearer " auth-token)}}
        {:keys [status headers body error] :as resp} @(http/get (str "https://registry-1.docker.io/v2/" repository "/tags/list") req-options)]
    (if error
      (throw (RuntimeException. error))
      (parse-string body true))))

(defn- p-v2-manifest [auth-token repository tag]
  (let [req-options {:timeout 2000
                     :headers {"Authorization" (str "Bearer " auth-token)}}
        {:keys [status headers body error] :as resp} @(http/get (str "https://registry-1.docker.io/v2/" repository "/manifest") req-options)]
    (if error
      (throw (RuntimeException. error))
      (parse-string body true))))


(defn image-ancestry [account password repository image-hash]
  (if (empty? image-hash)
    (do (prn "Empty image id - continuing...") [])
    (do (prn (str "finding ancestors in repo " repository " for image " image-hash "."))
        (some->
         (v2-authorize account password repository)
         (p-v2-image-ancestry image-hash)))))

(defn list-tags [account password repository]
  (some->
   (v2-authorize account password repository)
   (p-v2-list-tags repository)))

(defn manifest [account password repository tag]
  (some->
   (v2-authorize account password repository)
   (p-v2-manifest repository tag)))


(defn list-tags-for-all [account password repositories]
  (reduce
   (fn [acc repo-name] (union acc (list-tags account password repo-name)))
   {}
   repositories))
