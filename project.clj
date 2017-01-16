(defproject standalone-test-server "0.6.1"
  :description "An in-process server that can record requests.
               Useful for testing code that makes external http requests"
  :url "https://github.com/Mayvenn/standalone-test-server"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[codox "0.8.12"]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [ring/ring-codec "1.0.0"]
                 [ring/ring-jetty-adapter "1.3.2"]
                 [org.clojure/data.json "0.2.6"]]
  :codox {:include standalone-test-server.core
          :src-dir-uri "http://github.com/Mayvenn/standalone-test-server/blob/master/"
          :src-linenum-anchor-prefix "L"}
  :deploy-repositories [["releases" :clojars]]
  :profiles
  {:dev {:source-paths ["dev"]
         :dependencies [[clj-http "1.0.1"]
                        [org.clojure/tools.namespace "0.2.9"]]}})

