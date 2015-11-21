(ns standalone-test-server.core
  "Provides a ring handler that can record received requests."
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.codec :refer [form-decode]]
            [clojure.string :as string])
  (:import [java.io ByteArrayInputStream]))

(def ^:private default-handler
  (constantly {:status 200
               :headers {}
               :body ""}))

(def ^:private default-timeout 500)

(defn- wrap-query-params [request]
  (assoc request :query-params
         (into {}
               (form-decode (or (:query-string request) "")))))

(defn lazy-request-list [col timeout]
  (lazy-seq (if-let [next (and (first col)
                               (deref (first col) timeout nil))]
              (cons next (lazy-request-list (rest col) timeout))
              '())))

(defn recording-endpoint
  "Creates a ring handler that can record the requests it receives.

  Options:
  handler           The handler for the recording-endpoint to wrap. Defaults to
                    a handler that returns status 200 with an empty body.

  timeout           The timeout period for blocking on requests.  Defualt: 500ms

  Returns:
  [requests recording-handler]


  lazy-request-promises is a lazy seq of promises that a recorded requests.
  Instead of returning lazy-request-promises, a lazy-seq is returned.  This lazy seq
  will attempt to derefence the next request as it iterates the infinite seq of
  promises. When it attempts to deref the next promise, it will block for the timeout
  period (in milliseconds).  If deref timesout, the lazy-seq will be terminated.

  The requests are standard ring requests except that the :body will be a string
  instead of InputStream.

  Example invocations:
  ;;Waits for a single request for 1000ms
  (let [[requests endpoint] (recording-endpoint {:timeout 1000})]
    (first requests))

  ;;Waits 1000ms each for two requests
  (let [[requests endpoint] (recording-endpoint {:timeout 1000})]
    (take 2 requests))

  ;; returns a 404 response to the http client that hits this endpoint
  (recording-endpoint {:handler (constantly {:status 404 :headers {}})})"
  [& [{:keys [handler timeout]
       :or {handler default-handler
            timeout default-timeout}}]]
  (let [requests (repeatedly promise)
        request-count (atom 0)]
    [(lazy-request-list requests timeout)
     (fn [request]
       (let [request (wrap-query-params request)
             body-contents (-> request :body slurp)]
         (deliver (nth requests @request-count)
                  (assoc request :body body-contents))
         (swap! request-count inc)
         (handler (assoc request :body (ByteArrayInputStream. (.getBytes body-contents))))))]))

(defn standalone-server
  "Wrapper to start a standalone server through ring-jetty. Takes a ring handler
  and if desired, options to pass through to run-jetty.

  Example:
  (standalone-server handler {:port 4335})"
  [handler & [opts]]
  (jetty/run-jetty handler (merge {:port 4334 :join? false} opts)))

(defmacro with-standalone-server
  "A convenience macro to ensure a standalone-server is stopped.

  Example with standalone-server and recording-endpoint:
  (let [[requests endpoint] (recording-endpoint)]
    (with-standalone-server [server (standalone-server endpoint)]
      (http/get \"http://localhost:4334/endpoint\")
      (is (= 1 (count requests)))))"
  [bindings & body]
  (assert (vector? bindings) "bindings must be a vector")
  (assert (even? (count bindings)) "bindings must be an even number of forms")
  (cond
    (zero? (count bindings))
    `(do ~@body)

    (symbol? (bindings 0))
    `(let ~(subvec bindings 0 2)
       (try
         (with-standalone-server ~(subvec bindings 2) ~@body)
         (finally
           (.stop ~(bindings 0)))))))
