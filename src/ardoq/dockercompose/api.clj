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

(def ardoq-API-URL (or (System/getenv "ARDOQ_API_URL") "http://localhost:8080"))
(def ardoq-WEB-URL (or (System/getenv "ARDOQ_WEB_URL") "http://localhost:8080"))

(defn index [req]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (generate-string {:status "running"})})

(defn find-or-create-template [client]
  (if-let [template (first
                  (filter
                   #(= "Docker Compose" (:name %))
                   (client/find-all (client/map->Model {}) client)))]
    template
    (-> (client/map->Model (parse-string (slurp (io/resource "model.json")) true))
        (client/create client))))

(defn lookup-model [client workspace]
  (client/find-by-id (client/map->Model {:_id (:componentModel workspace)}) client))

(defn create-workspace [client wsname {:keys [_id] :as template}]
  (-> (client/->Workspace wsname "Auto generated workspace based on Docker Compose YAML file" _id)
      (assoc :views ["swimlane" "sequence" "integrations" "componenttree" "relationships" "tableview" "tagscape" "reader" "processflow"])
      (client/create client)))

(defn parse-yaml [yamlstr]
  (yaml/parse-string yamlstr))

(def ardoq-model-ref-type
  {:volumes_from 0
   :instance_of 1
   :links 3
   :parent_image 4
   :volumes 5
   :networks 5})


(defn yaml->container-components [yaml workspace model model-name->type-id]
  (mapv
   (fn [[k {:keys [image]}]]
     (->
      (client/->Component (name k) "" (:_id workspace) (:_id model) (:container model-name->type-id) nil)
      (assoc :dockerImage image)))
   yaml))


(defn yaml->network-components [yaml workspace model model-name->type-id]
  (map
    (fn [network]
      (client/->Component (name network) "" (:_id workspace) (:_id model) (:network model-name->type-id) nil))
    (keys (:networks yaml))))


(defn yaml->volume-components [yaml workspace model model-name->type-id]
  (map
    (fn [network]
      (client/->Component (name network) "" (:_id workspace) (:_id model) (:volume model-name->type-id) nil))
    (keys (:volumes yaml))))


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
                     (if (nil? target)
                       (prn (str "Reference not created. One or more mapping is missing " source-name ", " source " - " target-name ", " target))
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


(defn yaml->image-components [image-tag-names workspace model model-name->type-id]
  (mapv
    (fn [image-tag-name]
      (->
        (client/->Component image-tag-name "" (:_id workspace) (:_id model) (:image model-name->type-id) nil)))
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

(defn create-model-name->type-id-map [model]
  (into {} (map (fn [[k v]] [(keyword (str/lower-case (:name v))) k] ) (:root model))))


(defn handle-yaml-post [{{token "token", org "org", workspace-name "wsname", repos "repos" account "account" password "password"} :query-params
                         body :body :or {workspace-name "docker-compose" repos ""}}]
  (prn (str "Importing for org: " org))
  (prn (str "workspace-name: " workspace-name))
  (prn (str "repos " repos))

  (let [client (client/client {:url   ardoq-API-URL
                               :token token
                               :org   org})
        template (find-or-create-template client)
        workspace (create-workspace client workspace-name template)
        model (lookup-model client workspace)
        model-name->type-id (create-model-name->type-id-map model)
        yamlStr (slurp body)
        yaml (parse-yaml yamlStr)
        compose-version (:version yaml)
        services (if (= "2" compose-version) (:services yaml) yaml)

        ;; map from image names to Ardoq internal IDs
        container-name->component-id (-> (yaml->container-components services workspace model model-name->type-id)
                                         (create-resources client)
                                         (components->name-id-map))

        image-tags->container-hashes (->> (yaml->repo-names services)
                                          (union (remove clojure.string/blank? (clojure.string/split repos #"[, ]")))
                                          (distinct)
                                          (docker/list-tags-for-all account password))

        image-tags-with-ancestors (->> (yaml->image-tag-names services)
                                       (map (fn [image-tag] (image-tag-ancestry account password image-tags->container-hashes image-tag))))

        images-name->component-id (-> (mapcat (fn [e] e) image-tags-with-ancestors)
                                      (concat (yaml->image-tag-names services))
                                      (distinct)
                                      (yaml->image-components workspace model model-name->type-id)
                                      (create-resources client)
                                      (images->name-id-map))

        network-name->component-id (-> (yaml->network-components yaml workspace model model-name->type-id)
                                     (create-resources client)
                                     (components->name-id-map))

        volume-name->component-id (-> (yaml->volume-components yaml workspace model model-name->type-id)
                                    (create-resources client)
                                    (components->name-id-map))]

    ;; creating Ardoq relations for "volumes_from" section in yaml
    (->
     (yaml-reference->ardoq-relations services container-name->component-id workspace :volumes_from)
     (create-resources client))

    ;; creating Ardoq relations for "links" section in yaml
    (->
     (yaml-reference->ardoq-relations services container-name->component-id workspace :links)
     (create-resources client))

    ;; Relations for "volumes" section in yaml
    (->
      (yaml-reference->ardoq-relations
        services
        (clojure.set/union container-name->component-id volume-name->component-id)
        workspace :volumes)
      (create-resources client))

    (prn "----------------------------------")
    (prn network-name->component-id)
    (prn "----------------------------------")
    (clojure.set/union container-name->component-id network-name->component-id)
    (prn "----------------------------------")

    ;; Relations for "networks" section in yaml
    (->
      (yaml-reference->ardoq-relations
        services
        (clojure.set/union container-name->component-id network-name->component-id)
        workspace :networks)
      (create-resources client))

    ;; creating relations from Container to image
    (->
      services
      yaml->container-image-seq
      (container-image-seq->ardoq-relations container-name->component-id images-name->component-id workspace)
      (create-resources client))

    (->
      image-tags-with-ancestors
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
