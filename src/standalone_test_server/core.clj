(ns standalone-test-server.core
  (:require [ring.adapter.jetty :as jetty]))

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
  [& [{:keys [request-count handler]
       :or {request-count 1
            handler default-handler}}]]
  (let [requests (atom [])
        requests-count-reached (promise)]
    [(get-requests-wrapper requests-count-reached requests)
     (fn [request]
       (swap! requests conj (assoc request :body (-> request :body slurp)))
       (when (>= (count @requests) request-count)
         (deliver requests-count-reached true))
       (handler request))]))

(defn standalone-server
  [handler & [opts]]
  (jetty/run-jetty handler (merge {:port 4334 :join? false} opts)))

(defmacro with-standalone-server
  [bindings & body]
  `(let ~bindings
     (try
       ~@body
       (finally
         (.stop ~(bindings 0))))))
