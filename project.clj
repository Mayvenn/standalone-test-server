(defproject standalone-test-server "0.1.0-SNAPSHOT"
  :description "A standalone server that can record requests for test"
  :url "https://github.com/Mayvenn/standalone-test-server"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[s3-wagon-private "1.1.2"]]
  :repositories [["private" {:url "s3p://private-bucket-name/releases/"
                             :username :env
                             :passphrase :env
                             :sign-releases false}]]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring/ring-jetty-adapter "1.3.2"]]
  :profiles
  {:dev {:source-paths ["dev"]
         :dependencies [[clj-http "1.0.1"]
                        [org.clojure/tools.namespace "0.2.9"]]}})
