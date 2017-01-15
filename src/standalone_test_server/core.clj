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

(defn requests-meet?
  "Blocks until the given atom satisfies a predicate or the timeout has been reached.

  Returns true or false respectively.

  The predicate function takes the state of the atom as the only argument. It is
  tested after every change to the atom.

  Also note that the predicate function may be called on multiple threads simultaneously.

  ;; req-atom must have at least 2 requests before 3 seconds have elapsed.
  (assert-requests req-atom #(>= 2 (count %)) {:timeout 3000})
  "
  ([requests-atom pred] (requests-meet? requests-atom pred {}))
  ([requests-atom pred {:keys [timeout]
                        :or {timeout default-timeout}}]
   (let [prom (promise)
         id   (gensym)]
     (add-watch requests-atom id (fn [_ _ _ new-state]
                                   (when (pred new-state)
                                     (deliver prom true))))
     (let [met? (or (pred @requests-atom)
                    (deref prom timeout false))]
       (remove-watch requests-atom id)
       met?))))

(defn requests-quiescent
  "Blocks until the given requests atom has stopped growing for `for-ms`

  Returns nil.
  "
  ([requests-atom] (requests-quiescent requests-atom {}))
  ([requests-atom {:keys [for-ms] :or {for-ms default-timeout}}]
   (loop [len (count @requests-atom)]
     (when-let [grown? (requests-meet? requests-atom #(< len (count %)) {:timeout for-ms})]
       (recur (count @requests-atom))))))

(defn assert-requests
  "Blocks until the `requests-meet?` the predicate or the timeout has been reached.

  If the timeout has been reached an AssertionError will be thrown.

  ;; req-atom must have at least 2 requests before 3 seconds have elapsed.
  (assert-requests req-atom #(>= 2 (count %)) {:timeout 3000})
  "
  ([requests-atom pred] (assert-requests requests-atom pred {}))
  ([requests-atom pred opts]
   (when-not (requests-meet? requests-atom pred opts)
     (throw (AssertionError. "Failed to satisfy condition for requests within timeout")))))

(defn assert-requests-min-count
  "Convenience for calling (assert-requests req-atom #(<= min-count (count %)) options)"
  ([requests-atom min-count]
   (assert-requests-min-count requests-atom min-count {}))
  ([requests-atom min-count options]
   (assert-requests requests-atom #(<= min-count (count %)) options)))

(defn assert-requests-count
  "Convenience for calling (assert-requests req-atom #(= exact-count (count %)) options)"
  ([requests-atom exact-count]
   (assert-requests-count requests-atom exact-count {}))
  ([requests-atom exact-count options]
   (assert-requests requests-atom #(= exact-count (count %)) options)))

(defn recording-endpoint
  "Creates a ring handler that can record the requests it receives.

  Options:
  handler           The handler for the recording-endpoint to wrap. Defaults to
                    a handler that returns status 200 with an empty body.

  Returns:
  [requests-atom recording-handler]


  Returns an atom that contains a vector of requests received by the recording-handler.

  If you need to ensure a condition of the requests-atom is satisfied before
  deref-ing it, use `requests-meet?`, `assert-requests` or a related helper.

  The requests are standard ring requests except that the :body will be a string
  instead of InputStream.

  Example invocations:
  ;; Waits for a single request for 1000ms
  (let [[req-atom endpoint] (recording-endpoint)]
    (assert-requests req-atom first {:timeout 1000})
    (first @req-atom))

  ;; Waits up to 1000ms for two requests
  (let [[req-atom endpoint] (recording-endpoint {:timeout 1000})]
    (assert-requests req-atom second {:timeout 1000})
    (take 2 @req-atom))

  ;; Returns a 404 response to the http client that hits this endpoint
  (recording-endpoint {:handler (constantly {:status 404 :headers {}})})"
  [& [{:keys [handler]
       :or {handler default-handler}}]]
  (let [requests-atom (atom [])]
    [requests-atom
     (fn [request]
       (let [request (assoc request
                            :body (-> request :body slurp)
                            :query-params (into {}
                                                (some->> request
                                                         :query-string
                                                         form-decode)))]
         (swap! requests-atom conj request)
         (handler (update-in request [:body] #(ByteArrayInputStream. (.getBytes %))))))]))

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
      (requests-quiescent requests {:for-ms 50})
      (is (= 1 (count @requests)))))"
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
