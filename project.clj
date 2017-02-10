(defproject standalone-test-server "0.7.1-SNAPSHOT"
  :description "An in-process server that can record requests.
               Useful for testing code that makes external http requests"
  :url "https://github.com/Mayvenn/standalone-test-server"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-codox "0.10.2"]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [ring/ring-codec "1.0.0"]
                 [ring/ring-jetty-adapter "1.3.2"]
                 [org.clojure/data.json "0.2.6"]]
  :codox {:source-paths ["src"]
          :source-uri "http://github.com/Mayvenn/standalone-test-server/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}
          :doc-files ["README.md"]}
  :deploy-repositories [["releases" :clojars]]
  :profiles
  {:dev {:source-paths ["dev"]
         :dependencies [[clj-http "1.0.1"]
                        [org.clojure/tools.namespace "0.2.9"]]}})

