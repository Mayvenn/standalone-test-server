(ns standalone-test-server.query-test
  (:require [clj-http.client :as http]
            [standalone-test-server.query :refer :all]
            [standalone-test-server.core :refer :all]
            [clojure.test :refer :all])
  (:import [java.io ByteArrayInputStream]))

(def json-body-request-fixture {:ssl-client-cert nil,
                                :remote-addr "127.0.0.1",
                                :headers
                                {"host" "localhost:4334",
                                 "transfer-encoding" "chunked",
                                 "user-agent" "Apache-HttpClient/4.3.5 (java 1.5)",
                                 "accept-encoding" "gzip, deflate",
                                 "content-type" "application/json",
                                 "connection" "close"},
                                :server-port 4334,
                                :content-length nil,
                                :query-params {"a" "b"},
                                :character-encoding nil,
                                :uri "/endpoint",
                                :server-name "localhost",
                                :query-string "a=b",
                                :body "{\"test\":\"value\", \"second_test\":\"second_value\"}",
                                :scheme :http,
                                :request-method :post})

(def form-body-request-fixture {:ssl-client-cert nil,
                                :remote-addr "127.0.0.1",
                                :headers
                                {"host" "localhost:4334",
                                 "transfer-encoding" "chunked",
                                 "user-agent" "Apache-HttpClient/4.3.5 (java 1.5)",
                                 "accept-encoding" "gzip, deflate",
                                 "content-type" "application/x-www-form-urlencoded",
                                 "connection" "close"},
                                :server-port 4334,
                                :content-length nil,
                                :query-params {"a" "b"},
                                :character-encoding nil,
                                :uri "/endpoint",
                                :server-name "localhost",
                                :query-string "a=b",
                                :body "test=value&second_test=second_value",
                                :scheme :http,
                                :request-method :post})

(def timeout 10)

(defmacro test-both-query [expected count col & filters])

(deftest query-by-query-keys
  (let [requests [json-body-request-fixture
                  (assoc json-body-request-fixture :query-string "b=a")]]
    (is (= [json-body-request-fixture]
           (->> requests
                (with-query-keys #{"a"}))))))

(deftest query-by-uri
  (let [requests [json-body-request-fixture
                  (assoc json-body-request-fixture :uri "/not_endpoint")]]
    (is (= [json-body-request-fixture]
           (->> requests
                (with-uri "/endpoint"))))))

(deftest query-by-method
  (let [requests [(assoc json-body-request-fixture :request-method :get)
                  json-body-request-fixture]]
    (is (= [json-body-request-fixture]
           (->> requests
                (with-method :post))))))

(deftest query-by-json-body-keys
  (let [requests [(assoc json-body-request-fixture :body "{\"not_test\":\"value\"}")
                  json-body-request-fixture]]
    (is (= [json-body-request-fixture]
           (->> requests
                (with-body-keys #{"test" "second_test"}))))))

(deftest query-by-with-json-body-keys
  (let [requests [(assoc json-body-request-fixture :body "{\"not_test\":\"value\"}")
                  json-body-request-fixture]]
    (is (= [json-body-request-fixture]
           (->> requests
                (with-body-key-subset #{"test"}))))))

(deftest query-by-json-body-eq
  (let [requests [(assoc json-body-request-fixture :body "{\"test\":\"not_value\", \"second_test\":\"second_value\"}")
                  json-body-request-fixture]]
    (is (= [json-body-request-fixture ]
           (->> requests
                (with-body {"second_test" "second_value"
                            "test" "value"}))))))

(deftest query-by-form-body-keys
  (let [requests [(assoc form-body-request-fixture :body "not_test=value")
                  form-body-request-fixture]]
    (is (= [form-body-request-fixture ]
           (->> requests
                (with-body-keys #{"test" "second_test"}))))))

(deftest query-by-with-form-body-keys
  (let [requests [(assoc form-body-request-fixture :body "not_test=value")
                  form-body-request-fixture]]
    (is (= [form-body-request-fixture ]
           (->> requests
                (with-body-key-subset #{"test"}))))))

(deftest query-by-form-body-eq
  (let [requests [(assoc json-body-request-fixture :body "{\"test\":\"not_value\"}")
                  json-body-request-fixture]]
    (is (= [json-body-request-fixture]
           (->> requests
                (with-body {"second_test" "second_value"
                            "test" "value"}))))))

(deftest future-integration
  (let [[get-requests endpoint] (recording-endpoint)]
    (with-standalone-server [ss (standalone-server endpoint)]
      (http/post "http://localhost:4334/endpoint?a=b"
                 {:headers {:content-type "application/json"}
                  :body (ByteArrayInputStream. (.getBytes "{\"test\":\"value\"}"))})
      (http/get "http://localhost:4334/endpoint"
                 {})
      (http/post "http://localhost:4334/endpoint"
                 {:headers {:content-type "application/x-www-form-urlencoded"}
                  :body (ByteArrayInputStream. (.getBytes "test=value&second_test=second_value"))})
      (http/get "http://localhost:4334/not_endpoint?c=d"
                 {})
      (is (= ["{\"test\":\"value\"}" "test=value&second_test=second_value"]
             (->> get-requests
                  (with-body-key-subset #{"test"})
                  (with-method :post)
                  (map :body)))))))
