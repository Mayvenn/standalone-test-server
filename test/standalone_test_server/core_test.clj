(ns standalone-test-server.core-test
  (:require [clojure.test :refer :all]
            [standalone-test-server.core :refer :all]
            [standalone-test-server.query :refer :all]
            [clj-http.client :as http])
  (:import [java.io ByteArrayInputStream]
           [java.net ConnectException]))

(defn ^:private thread-run [f]
  (doto (Thread. f)
    (.start)))

(deftest recording-requests-with-body
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/post "http://localhost:4334/endpoint?a=b"
                 {:body (ByteArrayInputStream. (.getBytes "{\"test\":\"value\"}"))})
      (is (= "{\"test\":\"value\"}" (->> @requests
                                         (take 2)
                                         (first)
                                         :body))))))

(deftest recording-several-requests-with-body
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (dotimes [n 5] (http/post "http://localhost:4334/endpoint"
                                {:body (ByteArrayInputStream. (.getBytes (str "hello there #" n)))}))
      (is (= '("hello there #0"
               "hello there #1"
               "hello there #2"
               "hello there #3"
               "hello there #4")
             (->> @requests
                  (take 5)
                  (map :body)))))))

(deftest requests-meet?-returns-false-if-timeout-is-reached
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (is (not (requests-meet? requests #(<= 5 (count %)) {:timeout 10}))))))

(deftest waiting-until-requests-quiescent
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler {:port 4336})]
      (dotimes [n 4]
        (thread-run
         #(do
            (Thread/sleep (Math/pow 10 n))
            (try
              (http/post "http://localhost:4336/endpoint"
                         {:body (ByteArrayInputStream. (.getBytes (str "hello there #" n)))})
              (catch ConnectException e
                ;; Test may finish and close server before last request
                nil)))))
      (requests-quiescent requests {:for-ms 1000})
      (is (requests-min-count? requests 3)))))

(deftest requests-count?-succeeds-when-requests-have-been-made
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (is (not (requests-count? requests 1)))
      (http/get "http://localhost:4334/endpoint")
      (is (requests-count? requests 1)))))

(deftest requests-count?-succeeds-if-requests-start-with-given-number-of-requests
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (is (requests-count? requests 0 {:timeout 10})))))

(deftest responses-count?-can-be-used-to-wait-until-slow-handler-has-finished
  (let [processed?                     (promise)
        [responses handler] (recording-responses {:handler (fn [req]
                                                             (Thread/sleep 50)
                                                             (deliver processed? true)
                                                             {:status 200 :body ""})})]
    (with-standalone-server [ss (standalone-server handler)]
      (thread-run #(http/get "http://localhost:4334/endpoint"))
      (is (responses-count? responses 1))
      (is (realized? processed?)))))

(deftest recording-concurrent-requests-accurately
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (dotimes [n 5]
        (thread-run
         #(http/post "http://localhost:4334/endpoint"
                     {:body (ByteArrayInputStream. (.getBytes (str "hello there #" n)))})))
      (is (requests-count? requests 5 {:timeout 2000}))
      (is (= #{"hello there #0"
               "hello there #1"
               "hello there #2"
               "hello there #3"
               "hello there #4"}
             (set (map :body @requests)))))))

(deftest recording-without-a-request-returns-an-empty-vec
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (is (= [] @requests)))))

(deftest recording-parses-no-query-params-as-empty-map
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= {}
               (-> @requests
                   first
                   :query-params)))))))

(deftest recording-parses-query-params
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (let [resp (http/get "http://localhost:4334/endpoint?hello=world&array=0&array=2&array=3")]
        (is (= {"hello" "world"
                "array" ["0" "2" "3"]}
               (-> @requests
                   first
                   :query-params)))))))

(deftest recording-requests-without-body
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/get "http://localhost:4334/endpoint")
      (is (= "/endpoint" (-> @requests first :uri))))))

(deftest recording-requests-with-body
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/post "http://localhost:4334/endpoint"
                 {:body (ByteArrayInputStream. (.getBytes "hello there"))})
      (is (= "hello there" (-> @requests first :body))))))

(deftest recording-requests-preserves-input-stream-body-for-handler
  (let [inner-handler-body (promise)
        inner-handler (fn [req] (deliver inner-handler-body (:body req))
                        {:status 200 :body ""})
        [_ handler] (recording-requests {:handler inner-handler})]
    (with-standalone-server [ss (standalone-server handler)]
      (http/post "http://localhost:4334/endpoint"
                 {:body (ByteArrayInputStream. (.getBytes "hello there"))})
      (is (= "hello there" (slurp @inner-handler-body))))))

(deftest specifying-a-response-handler
  (let [response-handler (constantly {:status 201 :headers {}})
        [_ handler] (recording-requests {:handler response-handler})]
    (with-standalone-server [ss (standalone-server handler)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= 201 (:status resp)))))))

(deftest getting-recorded-requests
  (testing "returns requests so far when the expected request count has not been met"
    (let [[requests handler] (recording-requests {:request-count 2})]
      (with-standalone-server [ss (standalone-server handler)]
        (http/get "http://localhost:4334/endpoint")
        (is (= 1 (count @requests)))))))

(deftest running-on-different-port
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [ss (standalone-server handler {:port 4335})]
      (http/post "http://localhost:4335/endpoint?hello=world&a=b&array[]=4" {:headers {:content-type "application/json"}
                                                                             :body (ByteArrayInputStream. (.getBytes "{'hello':'wolrd'}"))})
      (is (= 1 (count @requests))))))

(deftest specifying-multiple-servers-together
  (testing "with-standalone-server allows multiple server bindings"
    (let [[requests handler] (recording-requests {:request-count 2})]
      (with-standalone-server [s1 (standalone-server handler)
                               s2 (standalone-server handler {:port 4335})]
        (http/get "http://localhost:4334/endpoint")
        (http/get "http://localhost:4335/endpoint")
        (is (= "/endpoint" (-> @requests first :uri)))
        (is (= 2 (count @requests))))

      (testing "while properly shutting the servers down"
        (is (thrown? java.net.ConnectException
                     (http/get "http://localhost:4334/endpoint")))
        (is (thrown? java.net.ConnectException
                     (http/get "http://localhost:4335/endpoint")))))))
