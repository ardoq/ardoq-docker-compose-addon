(ns ardoq.dockercompose.api
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :refer [resources]]
            [compojure.handler]
            [clj-yaml.core :as yaml]
            [ardoq.client :as client]
            [clojure.java.io :as io]
            [clojure.set :refer [union map-invert]]
            [cheshire.core :refer [generate-string parse-string]]
            [clojure.string :as str]
            [ardoq.dockercompose.docker :as docker]))

(def ardoq-API-URL (or (System/getenv "ARDOQ_API_URL") "http://dockerhost"))
(def ardoq-WEB-URL (or (System/getenv "ARDOQ_WEB_URL") "http://dockerhost"))

(defn index [req]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (generate-string {:status "running"})})

(defn find-or-create-model [client]
  (if-let [model (first
                  (filter
                   #(= "docker-compose" (:name %))
                   (client/find-all (client/map->Model {}) client)))]
    model
    (-> (client/map->Model (parse-string (slurp (io/resource "model.json")) true))
        (client/create client))))

(defn find-or-create-workspace [client wsname {:keys [_id] :as model}]
  (or
   (first
    (filter
     #(= wsname (:name %))
     (client/find-all (client/map->Workspace {}) client)))
   (-> (client/->Workspace wsname "Auto generated workspace based on Docker Compose YAML file" _id)
       (assoc :views ["swimlane" "sequence" "integrations" "componenttree" "relationships" "tableview" "tagscape" "reader" "processflow"])
       (client/create client))))

(defn parse-yaml [yamlstr]
  (yaml/parse-string yamlstr))

(def ardoq-model-component-type
  {:image "p1444219080853"
   :container "p1444219223126"})

(def ardoq-model-ref-type
  {:volumes_from 0
   :instance_of 1
   :links 3
   :parent_image 4})


(defn yaml->container-components [yaml workspace model]
  (mapv
   (fn [[k {:keys [image]}]]
     (->
      (client/->Component (name k) "" (:_id workspace) (:_id model) (:container ardoq-model-component-type) nil)
      (assoc :dockerImage image)))
   yaml))

(defn create-resources [resources client]
  (mapv
   (fn [r]
     (client/create r client))
   resources))

(defn yaml-reference->ardoq-relations [yaml containers-by-name workspace yamltag]
                                        ; source name to target name map
  (->> yaml
       (mapcat
        (fn [[k v]] (for [vf (yamltag v)] {(name k) vf})))
       (keep
        (fn [link] (let [source-name (first (first link))
                         source (containers-by-name source-name)
                         target-full-name (second (first link))
                         target-name (first (str/split (str/replace target-full-name #"\"" "") #":"))
                         target (containers-by-name target-name)]
                     (if-not (nil? target)
                       (client/->Reference (:_id workspace) source target)))))
       (map
        (fn [ref] (assoc ref :type (yamltag ardoq-model-ref-type))))))

(defn components->name-id-map [components]
  (into
   {}
   (map
    (fn [c] {(:name c) (:_id c)})
    components)))


(defn yaml->image-tag-names [yaml]
  (->>
    yaml
    (map
      (fn [[_ {:keys [image]}]] image))
    (remove nil?)))


(defn yaml->image-components [image-tag-names workspace model]
  (mapv
    (fn [image-tag-name]
      (->
        (client/->Component image-tag-name "" (:_id workspace) (:_id model) (:image ardoq-model-component-type) nil)))
      image-tag-names))

(defn yaml->repo-names [yaml]
  (map
    (fn [image-tag-name] (first (str/split image-tag-name #":")))
    (yaml->image-tag-names yaml)))


(defn images->name-id-map [images]
  (into
    {}
    (map
      (fn [c] {(:name c) (:_id c)})
      images)))


(defn yaml->container-image-seq [yaml]
  (keep
    (fn [[k {:keys [image]}]] (if (some? image) {(name k) image}))
      yaml))


(defn container-image-seq->ardoq-relations [container-image-seq containers-by-name images-by-name workspace]
  (->>
    container-image-seq
    (map
      (fn [container-image]
        (client/->Reference (:_id workspace)
                            (containers-by-name (first(first container-image)))
                            (images-by-name (second(first container-image))))))
    (map
      (fn [ref] (assoc ref :type (:instance_of ardoq-model-ref-type))))))

(defn image-parent-seq->ardoq-relations [image-parent-seq images-by-name workspace]
  (->>
    image-parent-seq
    (map
      (fn [image-parent]
        (client/->Reference (:_id workspace)
                            (images-by-name (first image-parent))
                            (images-by-name (second image-parent)))))
    (map
      (fn [ref] (assoc ref :type (:parent_image ardoq-model-ref-type))))))

(defn image-tag-ancestry [account password image-tags->container-hashes image-tag]
  (let [container-hashes->image-tags (map-invert image-tags->container-hashes)
        repository (first (clojure.string/split image-tag #":"))]
    (prn (str "Looking up image-tag-ancestry for " image-tag))
    (prn image-tags->container-hashes)
    (->> image-tag
        (image-tags->container-hashes)
        (docker/image-ancestry account password repository)
        (map (fn [hash] (container-hashes->image-tags hash)))
        (remove nil?))))

(defn pair-consecutive-items [image-tag-seq]
  (loop [image-tags image-tag-seq
         result []]
    (if (> 2 (count image-tags))
      result
      (recur (rest image-tags) (conj result [(first image-tags) (second image-tags)]))
      )))

(defn pair-concecutive-item-seq [item-seq]
  (mapcat
    (fn [e] (pair-consecutive-items e))
    item-seq))


(defn handle-yaml-post [{{token "token", org "org", workspace-name "wsname", repos "repos" account "account" password "password"} :query-params
                         body :body :or {workspace-name "docker-compose" repos ""}}]
  (prn (str "token: " token))
  (prn (str "org: " org))
  (prn (str "workspace-name: " workspace-name))
  (prn (str "repos " repos))


  (let [client (client/client {:url   ardoq-API-URL
                               :token token
                               :org   org})
        model (find-or-create-model client)
        workspace (find-or-create-workspace client workspace-name model)
        yamlStr (slurp body)
        yaml (parse-yaml yamlStr)

        ;; map from image names to Ardoq internal IDs
        container-name->component-id (-> (yaml->container-components yaml workspace model)
                                         (create-resources client)
                                         (components->name-id-map))

        image-tags->container-hashes (->> (yaml->repo-names yaml)
                                          (union (remove clojure.string/blank? (clojure.string/split repos #"[, ]")))
                                          (distinct)
                                          (docker/list-tags-for-all account password))

        image-tags-with-ancestors (->> (yaml->image-tag-names yaml)
                                       (map (fn [image-tag] (image-tag-ancestry account password image-tags->container-hashes image-tag))))

        images-name->component-id (-> (mapcat (fn [e] e) image-tags-with-ancestors)
                                      (concat (yaml->image-tag-names yaml))
                                      (distinct)
                                      (yaml->image-components workspace model)
                                      (create-resources client)
                                      (images->name-id-map))
        ]

    (prn image-tags-with-ancestors)

    ;; creating Ardoq relations for "volumes_from" section in yaml
    (->
     (yaml-reference->ardoq-relations yaml container-name->component-id workspace :volumes_from)
     (create-resources client))

    ;; creating Ardoq relations for "links" section in yaml
    (->
     (yaml-reference->ardoq-relations yaml container-name->component-id workspace :links)
     (create-resources client))

    ;; creating relations from Container tom image
    (->
      yaml
      yaml->container-image-seq
      (container-image-seq->ardoq-relations container-name->component-id images-name->component-id workspace)
      (create-resources client))

    (-> image-tags-with-ancestors
        (pair-concecutive-item-seq)
        (image-parent-seq->ardoq-relations images-name->component-id workspace)
        (create-resources client))


    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (generate-string {:status       "ok"
                                :workspaceURL (str ardoq-WEB-URL "/app/view/relationships/workspace/" (:_id workspace))})
     }))

(defroutes api
  (GET "/" [] index)
  (POST "/yaml" [] handle-yaml-post)
  (resources "/"))

(def app (-> api
             compojure.handler/api))
