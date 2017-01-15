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

(defn traffic-meets?
  "Blocks until the given traffic-atom satisfies a predicate or the timeout has
  been reached.

  Returns true or false respectively.

  The predicate function takes the state of the traffic-atom as the only argument. It is
  tested after every change to the traffic.

  Also note that the predicate function may be called on multiple threads simultaneously.

  ;; Reports whether traffic-atom has at least 2 items before 3 seconds have elapsed.
  (traffic-meets? traffic-atom #(<= 2 (count %)) {:timeout 3000})
  "
  ([traffic pred] (traffic-meets? traffic pred {}))
  ([traffic pred {:keys [timeout]
                        :or {timeout default-timeout}}]
   (let [prom (promise)
         id   (gensym)]
     (add-watch traffic id (fn [_ _ _ new-state]
                                   (when (pred new-state)
                                     (deliver prom true))))
     (let [met? (or (pred @traffic)
                    (deref prom timeout false))]
       (remove-watch traffic id)
       met?))))

(defn traffic-count?
  "Convenience for calling (traffic-meets? traffic-atom #(= exact-count (count %)) options)"
  ([traffic exact-count]
   (traffic-count? traffic exact-count {}))
  ([traffic exact-count options]
   (traffic-meets? traffic #(= exact-count (count %)) options)))

(defn traffic-min-count?
  "Convenience for calling (traffic-meets? traffic-atom #(<= min-count (count %)) options)"
  ([traffic min-count]
   (traffic-min-count? traffic min-count {}))
  ([traffic min-count options]
   (traffic-meets? traffic #(<= min-count (count %)) options)))

(defn traffic-quiescent
  "Blocks until the given traffic traffic has stopped growing for `for-ms`

  Returns nil.
  "
  ([traffic] (traffic-quiescent traffic {}))
  ([traffic {:keys [for-ms] :or {for-ms default-timeout}}]
   (loop [len (count @traffic)]
     (when-let [grown? (traffic-meets? traffic #(< len (count %)) {:timeout for-ms})]
       (recur (count @traffic))))))

(def requests-meet? "Synonym for traffic-meets?, but makes tests more legible" traffic-meets?)
(def requests-count? "Synonym for traffic-count?, but makes tests more legible" traffic-count?)
(def requests-min-count? "Synonym for traffic-min-count?, but makes tests more legible" traffic-min-count?)
(def requests-quiescent "Synonym for traffic-quiescent, but makes tests more legible" traffic-quiescent)
(def responses-meet? "Synonym for traffic-meets?, but makes tests more legible" traffic-meets?)
(def responses-count? "Synonym for traffic-count?, but makes tests more legible" traffic-count?)
(def responses-min-count? "Synonym for traffic-min-count?, but makes tests more legible" traffic-min-count?)
(def responses-quiescent "Synonym for traffic-quiescent, but makes tests more legible" traffic-quiescent)

(defn recording-traffic
  "Creates a ring handler that can record the traffic that flows through it.

  Options:
  handler  The handler for recording-traffic to wrap. Defaults to
           a handler that returns status 200 with an empty body.

  Returns:
  [{:requests  requests-atom
    :responses responses-atom}
   recording-handler]

  The requests-atom contains a vector of requests received by the
  recording-handler. Each item in the responses-atom will be a map of the
  `request` and its corresponding `response`. If the handler is slow, items will
  show up in the requests-atom before showing up in the responses-atom.

  The requests are standard ring requests except that the :body will be a string
  instead of InputStream.

  Example invocations:
  ;; Returns a 404 response to the http client that hits this handler
  (recording-traffic {:handler (constantly {:status 404 :headers {}})})

  See `with-standalone-server` for an example of how to use `recording-traffic`
  with a `standalone-server`."
  [& [{:keys [handler]
       :or   {handler default-handler}}]]
  (let [requests-atom  (atom [])
        responses-atom (atom [])]
    [{:requests requests-atom
      :responses responses-atom}
     (fn [request]
       (let [request (assoc request
                            :body (-> request :body slurp)
                            :query-params (into {}
                                                (some->> request
                                                         :query-string
                                                         form-decode)))]
         (swap! requests-atom conj request)
         (let [response (handler (update-in request [:body] #(ByteArrayInputStream. (.getBytes %))))]
           (swap! responses-atom conj {:request request :response response})
           response)))]))

(defn recording-requests
  "Like `recording-traffic` but the first item in the returned tuple is only the
  `requests`.

  If you need to ensure a condition of the requests-atom is satisfied before
  deref-ing it, use `requests-meet?`, `requests-count?` or a related helper.

  Example invocations:
  ;; Waits up to 1000ms for a single request
  (let [[requests handler] (recording-requests)]
    (is (requests-meet? requests first {:timeout 1000}))
    (first @requests))

  ;; Waits up to 1000ms for two requests
  (let [[requests handler] (recording-requests)]
    (is (requests-meet? requests second {:timeout 1000}))
    (take 2 @requests))"
  [& args]
  (let [[{:keys [requests]} handler] (apply recording-traffic args)]
    [requests handler]))

(def recording-endpoint "Deprecated synonym for `recording-requests`" recording-requests)

(defn recording-responses
  "Like `recording-traffic` but the first item in the returned tuple is only the
  `responses`.

  If you need to ensure a condition of the responses-atom is satisfied before
  deref-ing it, use `responses-meet?`, `responses-count?` or a related helper.

  Example invocations:
  ;; Waits up to 1000ms for a single response
  (let [[responses handler] (recording-responses)]
    (is (responses-meet? responses first {:timeout 1000}))
    (first @responses))

  ;; Waits up to 1000ms for two responses
  (let [[responses handler] (recording-responses)]
    (is (responses-meet? responses second {:timeout 1000}))
    (take 2 @responses))"
  [& args]
  (let [[{:keys [responses]} handler] (apply recording-traffic args)]
    [responses handler]))

(defn standalone-server
  "Wrapper to start a standalone server through ring-jetty. Takes a ring handler
  and if desired, options to pass through to run-jetty.

  Example:
  (standalone-server handler {:port 4335})"
  [handler & [opts]]
  (jetty/run-jetty handler (merge {:port 4334 :join? false} opts)))

(defmacro with-standalone-server
  "A convenience macro to ensure a standalone-server is stopped.

  Example with standalone-server and recording-requests:
  (let [[requests handler] (recording-requests)]
    (with-standalone-server [server (standalone-server handler)]
      (http/get \"http://localhost:4334/endpoint\")
      (is (requests-count? requests 1))))"
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
