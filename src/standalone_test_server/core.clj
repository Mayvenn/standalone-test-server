(ns standalone-test-server.core
  (:require [ring.adapter.jetty :as jetty]))

(def default-response {:status 200
                       :headers {}
                       :body ""})

(def default-handler (constantly default-response))

(defn recording-endpoint
  [complete-promise requests-complete? & {:keys [handler] :or {handler default-handler}}]
  (let [requests (atom [])]
    (fn [request]
      (let [slurped-request (assoc request :body (-> request (:body) (slurp)))]
        (swap! requests conj slurped-request)
        (when (requests-complete? @requests)
          (deliver complete-promise @requests))
        (handler slurped-request)))))

(defn matches-uri?
  [uri req]
  (->> req (:uri) (= uri)))

(defn requests-matching-uri
  [uri reqs]
  (filter (partial matches-uri? uri) reqs))

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
