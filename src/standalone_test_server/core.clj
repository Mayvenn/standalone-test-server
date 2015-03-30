(ns standalone-test-server.core
  (:require [ring.adapter.jetty :as jetty]))

(def default-response {:status 200
                       :headers {}
                       :body ""})

(def default-handler (constantly default-response))

(defn get-requests
  [requests-count-reached requests]
  (fn [& {:keys [timeout] :or {timeout 100}}]
    (and (deref requests-count-reached timeout true) @requests)))

(defn recording-endpoint
  [& {:keys [request-count handler] :or {request-count 1
                                         handler default-handler}}]
  (let [requests (atom [])
        requests-count-reached (promise)]
    [(get-requests requests-count-reached requests)
     (fn [request]
       (swap! requests conj (assoc request :body (-> request (:body) (slurp))))
       (when (>= (count @requests) request-count)
         (deliver requests-count-reached true))
       (handler request))]))

(defn standalone-server
  ([handler]
   (standalone-server handler {}))
  ([handler opts]
   (jetty/run-jetty handler (merge {:port 4334 :join? false} opts))))

(defmacro with-resource
  [bindings close-fn & body]
  `(let ~bindings
     (try
       ~@body
       (finally
         (~close-fn ~(bindings 0))))))

(defmacro with-standalone-server
  [bindings & body]
  `(with-resource ~bindings
     .stop
     ~@body))
