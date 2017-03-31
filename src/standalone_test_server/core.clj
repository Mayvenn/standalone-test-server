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
  "Blocks until the given `requests` atom satisfies a predicate or the timeout has been
  reached.

  Returns true or false respectively.

  The predicate function takes the state of the `requests` as the only argument. It is
  tested after every change to the `requests.`

  There is one optional argument:

  * `:timeout` the period of time (in milliseconds) to wait until returning false; defaults to 500.

  Also note that the predicate function may be called on multiple threads simultaneously.

  ```clj
  ;; Reports whether requests-atom has at least 2 items before 3 seconds have elapsed.
  (requests-meet? requests-atom #(<= 2 (count %)) {:timeout 3000})
  ```"
  ([requests pred] (requests-meet? requests pred {}))
  ([requests pred {:keys [timeout]
                   :or {timeout default-timeout}}]
   (let [prom (promise)
         id   (gensym)]
     (add-watch requests id (fn [_ _ _ new-state]
                              (when (pred new-state)
                                (deliver prom true))))
     (let [met? (or (pred @requests)
                    (deref prom timeout false))]
       (remove-watch requests id)
       met?))))

(defn requests-count?
  "Convenience for calling

  ```clj
  (requests-meet? requests-atom #(= exact-count (count %)) options)
  ```"
  ([requests exact-count]
   (requests-count? requests exact-count {}))
  ([requests exact-count options]
   (requests-meet? requests #(= exact-count (count %)) options)))

(defn requests-min-count?
  "Convenience for calling

  ```clj
  (requests-meet? requests-atom #(<= min-count (count %)) options)
  ```"
  ([requests min-count]
   (requests-min-count? requests min-count {}))
  ([requests min-count options]
   (requests-meet? requests #(<= min-count (count %)) options)))

(defn requests-quiescent
  "Blocks until the given requests requests has stopped growing for `for-ms`

  Returns nil.

  There is one optional argument:

  * `:for-ms` How long to wait after receiving the last request before declaring
    quiescence; defaults to 500.
  "
  ([requests] (requests-quiescent requests {}))
  ([requests {:keys [for-ms] :or {for-ms default-timeout}}]
   (loop [len (count @requests)]
     (when-let [grown? (requests-meet? requests #(< len (count %)) {:timeout for-ms})]
       (recur (count @requests))))))

(defn recording-endpoint
  "Creates a ring handler that can record the requests that flows through it.

  Options:

  * **`handler`** The handler for `recording-endpoint` to wrap. Defaults to a
    handler that returns status 200 with an empty body.

  Returns:
  ```clj
  [requests-atom recording-handler]
  ```

  The `requests-atom` contains a vector of requests received by the
  `recording-handler.`

  The requests are standard ring requests except that the `:body` will be a string
  instead of `InputStream.`

  Example invocations:

  ```clj
  ;; Returns a 404 response to the http client that hits this handler
  (recording-endpoint {:handler (constantly {:status 404 :headers {}})})
  ```

  See [[with-standalone-server]] for an example of how to use `recording-endpoint`
  with a [[standalone-server]]."
  [& [{:keys [handler] :or {handler default-handler}}]]
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
  "Wrapper to start a standalone server through `ring-jetty.` Takes a ring handler
  and if desired, options to pass through to `run-jetty`.

  The options default to `:port 4334` and `:join? false`.

  Example:
  ```clj
  (standalone-server handler {:port 4335})
  ```"
  [handler & [opts]]
  (jetty/run-jetty handler (merge {:port 4334 :join? false} opts)))

(defmacro with-standalone-server
  "A convenience macro to ensure a [[standalone-server]] is stopped.

  Example with [[standalone-server]] and [[recording-endpoint:]]
  ```clj
  (let [[requests handler] (recording-endpoint)]
    (with-standalone-server [server (standalone-server handler)]
      (http/get \"http://localhost:4334/endpoint\")
      (is (requests-count? requests 1))))
  ```"
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

(defn seq-handler
  "A helper function which iterates through a sequence of handlers using a new one for each call to the handler.

  Uses the default handler when out of handlers to use.

  Example

  (recording-endpoint
   {:timeout 1000
    :handler (seq-handler
              ;;first handler
              (wiretaps/http-response \"saddle_creek/PLGetInventory/no_products.json\")
              ;;second handler
              (wiretaps/http-response \"saddle_creek/PLGetInventory/products.json\"))})

  "
  [& handlers]
  (let [handlers-atom (atom handlers)]
    (fn [request]
      (if-let [next-handler (first @handlers-atom)]
        (let [response (next-handler request)]
          (swap! handlers-atom rest)
          response)
        (default-handler request)))))
