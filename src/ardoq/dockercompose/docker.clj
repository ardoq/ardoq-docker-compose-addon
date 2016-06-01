(ns ardoq.dockercompose.docker
  (:require
    [cheshire.core :refer [generate-string parse-string]]
    [clojure.set :refer [union map-invert]]
    [org.httpkit.client :as http]))


(defn v1-authorize [account password repository]
  (prn (str "authorizing for " repository))

  (let [options {:timeout 2000
                 :headers {"X-Docker-Token" "true"
                           "accept"         "application/json"}}
        auth-options (if (every? not-empty [account password])
          (assoc options :basic-auth [account password])
          options)
        asdf (prn auth-options)
        {:keys [status headers body error] :as resp} @(http/get (str "https://index.docker.io/v1/repositories/" repository "/images") auth-options)]
    (if error
      (throw (RuntimeException. error))
      (:x-docker-token headers))))


(defn v1-list-tags "Returns a map with image <repository:tag> as keys, and image hashes as values" [auth-token repository]
  (let [
        options {:timeout 30000
                 :headers {"authorization" (str "Token " auth-token)
                           "accept" "application/json"}}
        {:keys [status headers body error] :as resp} @(http/get (str "https://registry-1.docker.io/v1/repositories/" repository "/tags") options)]

    (prn (str "listing all tags of repository " repository))
    (prn status)
    (if error
      (prn error)
      (->>
        (parse-string body true)
        (reduce
          (fn [acc [k v]] (assoc acc (str repository ":" (name k)) v))
          {})))))


(defn list-tags [account password repository]
  (some->
    (v1-authorize account password repository)
    (v1-list-tags repository)))


(defn list-tags-for-all [account password repositories]
  (reduce
    (fn [acc repo-name] (union acc (list-tags account password repo-name)))
    {}
    repositories))


(defn v1-image-ancestry [auth-token imageid]
  (if (empty? imageid)
    (do (prn "Empty image ID!!") []))
    (let [
          options {:timeout 15000
                   :headers {"authorization" (str "Token " auth-token)
                             "accept"        "application/json"}}
          {:keys [status headers body error] :as resp} @(http/get (str "https://registry-1.docker.io/v1/images/" imageid "/ancestry") options)]

      (try
        (parse-string body true)
        (catch Exception e
          (prn (str "caught exception: " (.getMessage e)))
          []
          ))))


  (defn image-ancestry [account password repository image-hash]
    (if (empty? image-hash)
      (do (prn "Empty image id!")
          [])
      (do (prn (str "finding ancestors in repo " repository " for image " image-hash "."))
          (some->
            (v1-authorize account password repository)
            (v1-image-ancestry image-hash)))))


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

(defn v2-manifest [account password repository tag]

  (some->
    (v2-authorize account password repository)
    (p-v2-manifest repository tag)
    )
  )


(defn v2-list-tags [account password repository]

  (some->
    (v2-authorize account password repository)
    (p-v2-list-tags repository)
    )
  )








