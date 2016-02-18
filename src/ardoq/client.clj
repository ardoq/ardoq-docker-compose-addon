(ns ardoq.client
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]))


(defprotocol ArdoqResource
  (resource-path [this]))

(defrecord Workspace [name description componentModel]
  ArdoqResource
  (resource-path [_] "workspace"))

(defn workspace []
  (map->Workspace {}))

(defrecord Component [name description rootWorkspace model typeId parent]
  ArdoqResource
  (resource-path [_] "component"))

(defrecord Model [name description]
  ArdoqResource
  (resource-path [_] "model"))

(defn model []
  (map->Model {}))

(defrecord Reference [rootWorkspace source target]
  ArdoqResource
  (resource-path [_] "reference"))

(defrecord Field [label name type model componentType]
  ArdoqResource
  (resource-path [_] "field"))

(defrecord Tag [name description rootWorkspace components references]
  ArdoqResource
  (resource-path [_] "tag"))

(defn- to-component-type-map
  "Takes the root of a model and flattens it returning a typeId->type-map map"
  [model]
  (when model
    (letfn [(flatten-model [nodes res]
              (if (empty? nodes)
                res
                (let [{id :id children :children :as node} (first nodes)
                      r (assoc res (keyword id) (update-in node [:children] #(vec (map (comp name first) %))))
                      updated-children (map (fn [[_ i]] (assoc i :parent id)) children)]
                  (flatten-model (concat (rest nodes) updated-children) r))))]
      (flatten-model (map (fn [[_ i]] (assoc i :parent nil)) (:root model)) {}))))

(defn type-id-by-name [model type-name]
  (some->> (to-component-type-map model)
           (vals)
           (filter #(= type-name (:name %)))
           (first)
           (:id)))

(defn client [{:keys [url token org]}]
  (let [default-options {:timeout 2000
                         :query-params {:org org}}
        client {:url url
                :options (merge-with merge default-options {:headers {"Authorization" (str "Token token=" token)
                                                                      "Content-Type" "application/json"
                                                                      "User-Agent" "ardoq-clojure-client"}})}]
    (println "Client configuration: " client)
    client))

(defn- ok? [status]
  (and (< status 300)
       (> status 199)))

(defn- new-by-name
  [class-name & args]
  (clojure.lang.Reflector/invokeStaticMethod
   (clojure.lang.RT/classForName class-name)
   "create"
   (into-array Object args)))

(defn- coerce-response [resource data]
  (new-by-name (.getName (class resource)) data))

(defn find-by-id [resource client]
  (let [url (str (:url client) "/api/" (resource-path resource) "/" (:_id resource))
        {:keys [status body]} @(http/get url (:options client))]
    (cond
      (ok? status) (coerce-response resource (json/read-json body true))
      :else (throw (ex-info "client-exception" {:status status :body body})))))

(defn find-all [resource client & parameters]
  (let [url (str (:url client) "/api/" (resource-path resource) (first parameters))
        {:keys [status body]} @(http/get url (:options client))]
    (cond
      (ok? status) (map (partial coerce-response resource) (json/read-json body true))
      :else (throw (ex-info "client-exception" {:status status :body body})))))

(defn create [resource client]
  (let [url (str (:url client) "/api/" (resource-path resource))
        {:keys [status body]} @(http/post url (assoc (:options client) :body (json/write-str resource)))]
    (cond
      (ok? status) (coerce-response resource (json/read-json body true))
      :else (throw (ex-info "client-exception" {:status status :body body})))))

(defn update [resource client]
  (let [url (str (:url client) "/api/" (resource-path resource) "/" (:_id resource))
        {:keys [status body]} @(http/put url (assoc (:options client) :body (json/write-str resource)))]
    (cond
      (ok? status) (coerce-response resource (json/read-json body true))
      :else (throw (ex-info "client-exception" {:status status :body body})))))

(defn delete [resource client]
  (let [url (str (:url client) "/api/" (resource-path resource) "/" (:_id resource))
        {:keys [status body]} @(http/delete url (:options client))]
    (if-not (ok? status)
      (throw (ex-info "client-exception" {:status status :body body})))))

(defn aggregated-workspace [resource client]
  (let [url (str (:url client) "/api/" (resource-path resource) "/" (:_id resource) "/aggregated")
        {:keys [status body]} @(http/get url (:options client))]
    (if-not (ok? status)
      (throw (ex-info "client-exception" {:status status :body body})))))


(defn find-or-create-model [client model]
  (if-let [model (first (filter #(= model (:name %)) (find-all (map->Model {}) client)))]
    model
    (if-let [model (first (filter #(= model (:name %)) (find-all (map->Model {}) client "?includeCommon=true")))]
      (create model client))))
