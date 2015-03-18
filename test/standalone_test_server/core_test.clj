(ns standalone-test-server.core-test
  (:require [clojure.test :refer :all]
            [standalone-test-server.core :refer :all]
            [clj-http.client :as http])
  (:import [java.io ByteArrayInputStream]))

(def check-for-one-request #(= 1 (count %)))

(deftest recording-requets-without-body
  (let [requests (promise)
        endpoint (recording-endpoint requests check-for-one-request)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/get "http://localhost:4334/endpoint")
      (is (= "/endpoint" (-> (deref requests 1000 []) (first) (:uri)))))))

(deftest recording-requests-with-body
  (let [requests (promise)
        endpoint (recording-endpoint requests check-for-one-request)]
    (with-standalone-server [ss (standalone-server (recording-endpoint requests check-for-one-request))]
      (http/post "http://localhost:4334/endpoint"
                 {:body (ByteArrayInputStream. (.getBytes "hello there"))})
      (is (= "hello there" (-> (deref requests 1000 []) (first) (:body)))))))

(deftest specifying-a-response-handler
  (let [requests (promise)
        response-handler (constantly {:status 201 :headers {}})
        endpoint (recording-endpoint requests check-for-one-request :handler response-handler)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= 201 (:status resp)))))))
