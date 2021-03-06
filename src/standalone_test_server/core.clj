(ns standalone-test-server.core
  "Provides a ring handler that can record received requests."
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.codec :refer [form-decode]]
            [clojure.core.async :as async]
            [clojure.string :as string])
  (:import [java.io ByteArrayInputStream]))

(def ^:private default-handler
  (constantly {:status 200
               :headers {}
               :body ""}))

(def ^:private default-timeout 500)

(defn requests-meet?
  "DEPRECATED: Use [[txfm-requests]] instead

  Blocks until the given `requests` atom satisfies a predicate or the timeout has been
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
  {:deprecated "0.7.2"}
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
  "DEPRECATED: Use [[txfm-requests]] instead

  Convenience for calling

  ```clj
  (requests-meet? requests-atom #(= exact-count (count %)) options)
  ```"
  {:deprecated "0.7.2"}
  ([requests exact-count]
   (requests-count? requests exact-count {}))
  ([requests exact-count options]
   (requests-meet? requests #(= exact-count (count %)) options)))

(defn requests-min-count?
  "DEPRECATED: Use [[txfm-requests]] instead

  Convenience for calling

  ```clj
  (requests-meet? requests-atom #(<= min-count (count %)) options)
  ```"
  {:deprecated "0.7.2"}
  ([requests min-count]
   (requests-min-count? requests min-count {}))
  ([requests min-count options]
   (requests-meet? requests #(<= min-count (count %)) options)))

(defn requests-quiescent
  "DEPRECATED: Use [[txfm-requests]] instead

  Blocks until the given requests requests has stopped growing for `for-ms`

  Returns nil.

  There is one optional argument:

  * `:for-ms` How long to wait after receiving the last request before declaring
    quiescence; defaults to 500.
  "
  {:deprecated "0.7.2"}
  ([requests] (requests-quiescent requests {}))
  ([requests {:keys [for-ms] :or {for-ms default-timeout}}]
   (loop [len (count @requests)]
     (when-let [grown? (requests-meet? requests #(< len (count %)) {:timeout for-ms})]
       (recur (count @requests))))))

(defn recording-endpoint
  "DEPRECATED: Use [[with-requests-chan]] instead.

  Creates a ring handler that can record the requests that flows through it.

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
  ```"
  {:deprecated "0.7.2"}
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
         (handler (update-in request [:body] #(ByteArrayInputStream. (.getBytes ^String %))))))]))

(defn txfm-requests
  "Accepts a channel of `requests` and a transducing function `xf` which can
  `filter`, `take`, or `drop` requests. Especially useful to centralize the
  transformation of requests bodies, using `(map #(update :body % ...))`.

  Blocks until the `xf` is reduced or the timeout has been reached. As such, to
  avoid unnecessary pauses, `take` as many requests as you expect to receive.

  There is one optional argument:

  * **`:timeout`** the period of time (in milliseconds) to wait until returning what
    has been fetched so far; defaults to 500.

  ```clj
  ;; Returns upto 2 requests, as many as are produced within 3 seconds.
  (txfm-requests requests-chan (take 2) {:timeout 3000})
  ```"
  ([requests xf] (txfm-requests requests xf {:timeout default-timeout}))
  ([requests xf {:keys [timeout]}]
   {:pre [(number? timeout)
          (pos? timeout)]}
   (let [f        (xf conj)
         end-time (+ (System/currentTimeMillis) timeout)]
     (loop [acc (f)]
       ;; Check at least every 100ms to see if we're past the timeout
       (let [[val _] (async/alts!! [requests (async/timeout 100)])
             result  (if val (f acc val) acc)]
         (cond
           (reduced? result)                       @result
           (> (System/currentTimeMillis) end-time) result
           :else                                   (recur result)))))))

(defn txfm-request
  "Convenience for extracting the first request in a channel of `requests` which
  satisifies the transform `tx`. With no transform, or a transform that doesn't
  filter results, just returns the first request."
  ([requests] (txfm-request requests conj))
  ([requests xf] (txfm-request requests xf {:timeout default-timeout}))
  ([requests xf opts] (first (txfm-requests requests
                                            (comp xf (take 1))
                                            opts))))

(defn with-requests-chan
  "Creates a ring handler that can record the requests that flow through it.

  Options:

  * **`handler`** The handler for `with-requests-chan`` to wrap. Defaults to a
    handler that returns status 200 with an empty body.
  * **`buf-or-n`** The buffer configuration for the channel, defaults to 100.

  Returns:
  ```clj
  [requests-chan wrapped-handler]
  ```

  The `requests-chan` holds the requests received by the handler.
  [[txfm-requests]] can extract the requests from it.

  The requests are standard ring requests except that the `:body` will be a string
  instead of `InputStream.`

  Example invocations:

  ```clj
  ;; Returns a 404 response to the http client that hits this handler
  (with-requests-chan (constantly {:status 404 :headers {}}))
  ```"
  ([] (with-requests-chan default-handler))
  ([handler] (with-requests-chan handler 100))
  ([handler buf-or-n]
   (let [requests-chan (async/chan buf-or-n)]
     [requests-chan
      (fn [request]
        (let [request (assoc request
                             :body (-> request :body slurp)
                             :query-params (into {}
                                                 (some->> request
                                                          :query-string
                                                          form-decode)))]
          (async/>!! requests-chan request)
          (handler (update-in request [:body] #(ByteArrayInputStream. (.getBytes ^String %))))))])))

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

  Example with [[standalone-server]] and [[with-requests-chan]]
  ```clj
  (let [[requests handler] (with-requests-chan)]
    (with-standalone-server [server (standalone-server handler)]
      (http/get \"http://localhost:4334/endpoint\")
      (is (txfm-request requests))))
  ```"
  [bindings & body]
  (assert (vector? bindings) "bindings must be a vector")
  (assert (even? (count bindings)) "bindings must be an even number of forms")
  (cond
    (zero? (count bindings))
    `(do ~@body)

    (symbol? (bindings 0))
    (let [tagged-server (vary-meta (first bindings) assoc :tag `org.eclipse.jetty.server.Server)]
      `(let [~tagged-server ~(second bindings)]
         (try
           (with-standalone-server ~(subvec bindings 2) ~@body)
           (finally
             (.stop ~tagged-server)))))))

(defn seq-handler
  "A helper function which iterates through a sequence of handlers using a new one for each call to the handler.

  Uses the default handler when out of handlers to use.

  Example

  ```clj
  (with-requests-chan (seq-handler
                        ;;first handler
                        (fn [req] {:status 200 :body \"ok\"})
                        ;;second handler
                        (fn [req] {:status 400 :body \"bad\"})))
  ```"
  [& handlers]
  (let [handlers-atom (atom handlers)]
    (fn [request]
      (locking handlers-atom
        (if-let [next-handler (first @handlers-atom)]
          (let [response (next-handler request)]
            (swap! handlers-atom rest)
            response)
          (default-handler request))))))
