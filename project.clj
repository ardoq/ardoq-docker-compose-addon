(defproject ardoq-docker-compose "1.0"
  :description "Docker Compose add-on for Ardoq"
  :url "http://ardoq.com"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :main ardoq.dockercompose.server
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [http-kit "2.1.19"]
                 [com.stuartsierra/component "0.2.3"]
                 [compojure "1.4.0"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring/ring-core "1.4.0"]
                 [clj-yaml "0.4.0"]
                 [cheshire "5.4.0"]]
  :uberjar {:aot  [ardoq.dockercompose.server]
            :main ardoq.dockercompose.server}
  :profiles {:dev {:plugins      [[lein-cljsbuild "1.0.6"]]
                   :dependencies [[reloaded.repl "0.1.0"]]
                   :source-paths ["dev"]
                   :cljsbuild    {:builds
                                  [{:source-paths ["src" "dev"]
                                    :compiler     {:output-to            "target/classes/public/app.js"
                                                   :output-dir           "target/classes/public/out"
                                                   :optimizations        :none
                                                   :recompile-dependents true
                                                   :source-map           true
                                                   :pretty-print         true}}]}}}
  :repl-options {:init-ns user,
                 :timeout 120000})

