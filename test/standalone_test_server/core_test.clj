(ns standalone-test-server.core-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [standalone-test-server.core :refer :all])
  (:import java.net.ConnectException))

(deftest request-with-body
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/post "http://localhost:4334/endpoint?a=b"
                 {:body "{\"test\":\"value\"}"})
      (is (= "{\"test\":\"value\"}" (->> requests txfm-request :body))))))

(deftest request-without-body
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/get "http://localhost:4334/endpoint")
      (is (= "/endpoint" (-> requests txfm-request :uri))))))

(deftest several-requests-with-body
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (dotimes [n 5]
        (http/post "http://localhost:4334/endpoint"
                   {:body (str "hello there #" n)}))
      (is (= '("hello there #0"
               "hello there #1"
               "hello there #2"
               "hello there #3"
               "hello there #4")
             (txfm-requests requests (comp
                                      (take 5)
                                      (map :body))))))))

(deftest returns-as-much-as-it-has-if-timeout-is-reached
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/get "http://localhost:4334/endpoint")
      (is ["/endpoint"]
          (txfm-requests requests (comp (take 5) (map :uri)) {:timeout 10})))))

(deftest waiting-until-requests-quiescent
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler {:port 4336})]
      (dotimes [n 4]
        (future
          (Thread/sleep (Math/pow 10 n))
          (try
            (http/get "http://localhost:4336/endpoint")
            (catch ConnectException e
              ;; Test may finish and close server before last request
              nil))))
      ;; with timeout 900, txfm-requests waits for up to 900ms for all requests,
      ;; which means it will short-circuit between 3rd request at 100ms and 4th
      ;; request at 1s
      (is (= 3 (count (txfm-requests requests conj {:timeout 900})))))))

(deftest requests-can-be-fetched-when-requests-have-been-made
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (is (nil? (txfm-request requests)))
      (http/get "http://localhost:4334/endpoint")
      (is (txfm-request requests)))))

(deftest gathering-concurrent-requests-accurately
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (dotimes [n 5]
        (future
          (http/post "http://localhost:4334/endpoint"
                     {:body (str "hello there #" n)})))
      (is (= #{"hello there #0"
               "hello there #1"
               "hello there #2"
               "hello there #3"
               "hello there #4"}
             (set (txfm-requests requests (comp (take 5)
                                                (map :body)))))))))

(deftest without-a-request-returns-an-empty-vec
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (is (= [] (txfm-requests requests conj))))))

(deftest parses-no-query-params-as-empty-map
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (let [resp (http/get "http://localhost:4334/endpoint")]
        (is (= {}
               (-> requests
                   txfm-request
                   :query-params)))))))

(deftest parses-query-params
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler)]
      (let [resp (http/get "http://localhost:4334/endpoint?hello=world&array=0&array=2&array=3")]
        (is (= {"hello" "world"
                "array" ["0" "2" "3"]}
               (-> requests
                   txfm-request
                   :query-params)))))))

(deftest preserves-input-stream-body-for-handler
  (let [handler-body (promise)
        unsafe-handler (fn [req]
                         (deliver handler-body (:body req))
                         {:status 200 :body ""})
        [_ handler] (with-requests-chan unsafe-handler)]
    (with-standalone-server [ss (standalone-server handler)]
      (http/post "http://localhost:4334/endpoint" {:body "hello there"})
      (is (= "hello there" (slurp @handler-body))))))

(deftest specifying-a-response-handler
  (with-standalone-server [ss (standalone-server (constantly {:status 201 :headers {}}))]
    (let [resp (http/get "http://localhost:4334/endpoint")]
      (is (= 201 (:status resp))))))

(deftest specifying-a-sequence-of-response-handlers
  (let [response-handler1 (constantly {:status 201 :headers {} :body "resp-handler-1"})
        response-handler2 (constantly {:status 201 :headers {} :body "resp-handler-2"})
        response-handler3 (constantly {:status 201 :headers {} :body "resp-handler-3"})]
    (with-standalone-server [ss (standalone-server (seq-handler response-handler1
                                                                response-handler2
                                                                response-handler3))]
      (let [resp1 (http/get "http://localhost:4334/endpoint")
            resp2 (http/get "http://localhost:4334/endpoint")
            resp3 (http/get "http://localhost:4334/endpoint")
            resp4 (http/get "http://localhost:4334/endpoint")]
        (is (= 201 (:status resp1)))
        (is (= 201 (:status resp2)))
        (is (= 201 (:status resp3)))
        (is (= 200 (:status resp4)))
        (is (= "resp-handler-1" (-> resp1 :body)))
        (is (= "resp-handler-2" (-> resp2 :body)))
        (is (= "resp-handler-3" (-> resp3 :body)))
        (is (= "" (-> resp4 :body)))))))

(deftest specifying-a-sequence-of-async-response-handlers
  (let [response-handlers (map #(constantly {:status 201 :headers {} :body (str "resp-handler-" %)}) (range 4))]
    (with-standalone-server [ss (standalone-server (apply seq-handler response-handlers))]
      (let [resps (map deref [(future (http/get "http://localhost:4334/endpoint"))
                              (future (http/get "http://localhost:4334/endpoint"))
                              (future (http/get "http://localhost:4334/endpoint"))
                              (future (http/get "http://localhost:4334/endpoint"))])]
        (is (= (repeat 4 201)
               (map :status resps)))
        (is (= #{"resp-handler-0" "resp-handler-1" "resp-handler-2" "resp-handler-3"}
               (set (map :body resps))))))))

(deftest running-on-different-port
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [ss (standalone-server handler {:port 4335})]
      (http/get "http://localhost:4335/endpoint")
      (is (txfm-request requests)))))

(deftest specifying-multiple-servers-together
  (testing "with-standalone-server allows multiple server bindings"
    (let [[requests handler] (with-requests-chan)]
      (with-standalone-server [s1 (standalone-server handler)
                               s2 (standalone-server handler {:port 4335})]
        (http/get "http://localhost:4334/endpoint")
        (http/get "http://localhost:4335/endpoint")
        (is (= [4334 4335]
               (txfm-requests requests (comp
                                        (take 2)
                                        (map :server-port))))))

      (testing "while properly shutting the servers down"
        (is (thrown? java.net.ConnectException
                     (http/get "http://localhost:4334/endpoint")))
        (is (thrown? java.net.ConnectException
                     (http/get "http://localhost:4335/endpoint")))))))
