(ns standalone-test-server.core-test
  (:require [clojure.test :refer :all]
            [standalone-test-server.core :refer :all]
            [clj-http.client :as http])
  (:import [java.io ByteArrayInputStream]))

(deftest recording-requests-without-body
  (let [[get-requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/get "http://localhost:4334/endpoint")
      (is (= "/endpoint" (-> (get-requests) (first) (:uri)))))))

(deftest recording-requests-with-body
  (let [[get-requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/post "http://localhost:4334/endpoint"
                 {:body (ByteArrayInputStream. (.getBytes "hello there"))})
      (is (= "hello there" (-> (get-requests) (first) (:body)))))))

(deftest specifying-a-response-handler
  (let [response-handler (constantly {:status 201 :headers {}})
        [_ endpoint] (recording-endpoint :handler response-handler)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= 201 (:status resp)))))))

(deftest getting-recorded-requests
  (testing "returns requests so far when the expected request count has not been met"
    (let [[get-requests endpoint] (recording-endpoint :request-count 2)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/get "http://localhost:4334/endpoint")
      (is (= 1 (count (get-requests :timeout 10))))))))

(deftest running-on-different-port
  (let [[get-requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint {:port 4335})]
      (http/get "http://localhost:4335/endpoint")
      (is (= 1 (count (get-requests :timeout 10)))))))
