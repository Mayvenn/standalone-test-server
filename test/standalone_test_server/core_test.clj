(ns standalone-test-server.core-test
  (:require [clojure.test :refer :all]
            [standalone-test-server.core :refer :all]
            [standalone-test-server.query :refer :all]
            [clj-http.client :as http])
  (:import [java.io ByteArrayInputStream]))

(defn ^:private thread-run [f]
  (doto (Thread. f)
    (.start)))

(deftest recording-requests-with-body
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/post "http://localhost:4334/endpoint?a=b"
                 {:body (ByteArrayInputStream. (.getBytes "{\"test\":\"value\"}"))})
      (is (= "{\"test\":\"value\"}" (->> requests
                                         (take 2)
                                         (first)
                                         :body))))))

(deftest recording-several-requests-with-body
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (dotimes [n 5] (http/post "http://localhost:4334/endpoint"
                                {:body (ByteArrayInputStream. (.getBytes (str "hello there #" n)))}))
      (is (= '("hello there #0"
               "hello there #1"
               "hello there #2"
               "hello there #3"
               "hello there #4")
             (->> requests
                  (take 5)
                  (map :body)))))))

(deftest recording-concurrent-requests-accurately
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (dotimes [n 5]
        (thread-run
         #(http/post "http://localhost:4334/endpoint"
                     {:body (ByteArrayInputStream. (.getBytes (str "hello there #" n)))})))
      (is (= #{"hello there #0"
               "hello there #1"
               "hello there #2"
               "hello there #3"
               "hello there #4"}
             (->> requests
                  (take 5)
                  (map :body)
                  (set)))))))

(deftest recording-without-a-request-returns-a-never-resolved-promise
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (is (= '() requests)))))

(deftest recording-parses-no-query-params-as-empty-map
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= {}
               (-> requests
                   first
                   :query-params)))))))

(deftest recording-parses-query-params
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (let [resp (http/get "http://localhost:4334/endpoint?hello=world&array=0&array=2&array=3")]
        (is (= {"hello" "world"
                "array" ["0" "2" "3"]}
               (-> requests
                   first
                   :query-params)))))))

(deftest recording-requests-without-body
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/get "http://localhost:4334/endpoint")
      (is (= "/endpoint" (-> requests first :uri))))))

(deftest recording-requests-with-body
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/post "http://localhost:4334/endpoint"
                 {:body (ByteArrayInputStream. (.getBytes "hello there"))})
      (is (= "hello there" (-> requests first :body))))))

(deftest recording-requests-preserves-input-stream-body-for-handler
  (let [inner-handler-body (promise)
        inner-handler (fn [req] (deliver inner-handler-body (:body req))
                        {:status 200 :body ""})
        [requests endpoint] (recording-endpoint {:handler inner-handler})]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/post "http://localhost:4334/endpoint"
                 {:body (ByteArrayInputStream. (.getBytes "hello there"))})
      (is (= "hello there" (slurp @inner-handler-body))))))

(deftest specifying-a-response-handler
  (let [response-handler (constantly {:status 201 :headers {}})
        [_ endpoint] (recording-endpoint {:handler response-handler})]
    (with-standalone-server [ss (standalone-server endpoint)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= 201 (:status resp)))))))

(deftest getting-recorded-requests
  (testing "returns requests so far when the expected request count has not been met"
    (let [[requests endpoint] (recording-endpoint {:request-count 2})]
      (with-standalone-server [ss (standalone-server endpoint)]
        (http/get "http://localhost:4334/endpoint")
        (is (= 1 (count requests)))))))

(deftest running-on-different-port
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint {:port 4335})]
      (http/post "http://localhost:4335/endpoint?hello=world&a=b&array[]=4" {:headers {:content-type "application/json"}
                                                                             :body (ByteArrayInputStream. (.getBytes "{'hello':'wolrd'}"))})
      (is (= 1 (count requests))))))

(deftest specifying-multiple-servers-together
  (testing "with-standalone-server allows multiple server bindings"
    (let [[requests endpoint] (recording-endpoint {:request-count 2})]
      (with-standalone-server [s1 (standalone-server endpoint)
                               s2 (standalone-server endpoint {:port 4335})]
        (http/get "http://localhost:4334/endpoint")
        (http/get "http://localhost:4335/endpoint")
        (is (= "/endpoint" (-> requests first :uri)))
        (is (= 2 (count requests))))

      (testing "while properly shutting the servers down"
        (is (thrown? java.net.ConnectException
                     (http/get "http://localhost:4334/endpoint")))
        (is (thrown? java.net.ConnectException
                     (http/get "http://localhost:4335/endpoint")))))))
