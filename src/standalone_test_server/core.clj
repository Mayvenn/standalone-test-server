(ns standalone-test-server.core
  "Provides a ring handler that can record received requests."
  (:require [ring.adapter.jetty :as jetty])
  (:import [java.io ByteArrayInputStream]))

(def ^:private default-handler
  (constantly {:status 200
               :headers {}
               :body ""}))

(defn- get-requests-wrapper
  [requests-count-reached requests]
  (fn [& [{:keys [timeout]
           :or {timeout 1000}}]]
    (and (deref requests-count-reached timeout true) @requests)))

(defn recording-endpoint
  "Creates a ring handler that can record the requests it receives.

  Options:
  request-count     Number of requests to wait for before retrieve-requests returns.
                    Defaults to 1.
  handler           The handler for the recording-endpoint to wrap. Defaults to
                    a handler that returns status 200 with an empty body.

  Returns:
  [retrieve-requests recording-handler]

  retrieve-requests is a function that returns the recorded requests. As soon as the
  above request-count is reached, this function will return. If the request-count
  is not reached, retrieve-requests will return all recorded requests so far after a
  timeout. The timeout is 1000 ms by default and can be customized at invocation:
    (retrieve-requests {:timeout 1500})
  The requests are standard ring requests except that the :body will be a string
  instead of InputStream.

  Example invocations:
  ;; waits for two requests or timeout
  (recording-endpoint {:request-count 2})

  ;; returns a 404 response to the http client that hits this endpoint
  (recording-endpoint {:handler (constantly {:status 404 :headers {}})})"
  [& [{:keys [request-count handler]
       :or {request-count 1
            handler default-handler}}]]
  (let [requests (atom [])
        requests-count-reached (promise)]
    [(get-requests-wrapper requests-count-reached requests)
     (fn [request]
       (let [body-contents (-> request :body slurp)]
         (swap! requests conj (assoc request :body body-contents))
         (when (>= (count @requests) request-count)
           (deliver requests-count-reached true))
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
  (let [[retrieve-requests endpoint] (recording-endpoint)]
    (with-standalone-server [server (standalone-server endpoint)]
      (http/get \"http://localhost:4334/endpoint\")
      (is (= 1 (count (retrieve-requests))))))"
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
