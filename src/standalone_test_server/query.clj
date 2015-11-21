(ns standalone-test-server.query
  (:require [clojure.set :as set]
            [clojure.data.json :as json]
            [ring.util.codec :as ring]))

(def json-parser json/read-str)
(def form-parser ring/form-decode)
(def xml-parser nil)

(def content-type->parser
  {"application/json" json-parser
   "application/xml" xml-parser
   "text/xml" xml-parser
   "application/x-www-form-urlencoded" form-parser
   nil {}})

(defn ^:private =filter [value & get-fns]
  (partial filter #(= value ((apply comp (reverse get-fns)) %))))

(defn compare-in [m keypath comp-fn val]
  (comp-fn val (get m keypath)))

(defn with-uri [uri col]
  (filter #(= uri (:uri %)) col))

(defn with-method [method col]
  (filter #(= method (:request-method %))
          col))

(defn with-query-keys [key-set col]
  {:pre [(set? key-set)]}
  (filter #(= key-set
              (set (keys (form-parser (:query-string %)))))
          col))

(defn with-query-keys [key-set col]
  {:pre [(set? key-set)]}
  (filter #(set/subset? key-set
                        (set (keys (form-parser (:query-string %)))))
          col))

(defn with-query-params [kv-map col]
  (filter #(= kv-map (form-parser (:query-string %))) col))

(defn with-body-keys [body-keys col]
  {:pre [(set? body-keys)]}
  (filter (fn [request]
            (when-let [parser (-> (get-in request [:headers "content-type"])
                                  content-type->parser)]
              (prn (set (keys (parser (:body request)))))
              (= body-keys
                 (set (keys (parser (:body request)))))))
          col))

(defn with-body-keys [body-keys col]
  {:pre [(set? body-keys)]}
  (filter (fn [request]
            (when-let [parser (-> (get-in request [:headers "content-type"])
                                  content-type->parser)]
              (set/subset? body-keys
                           (set (keys (parser (:body request)))))))
          col))

(defn with-body [body col]
  (filter  (fn [request]
             (when-let [parser (-> (get-in request [:headers "content-type"])
                                   content-type->parser)]
               (= body
                  (parser (:body request)))))
           col))

;;Done Fetch requests by URI and http method
;;Done Fetch requests that have a given query param
;;Done Fetch requests that have a given query param & value
;;Done Fetch requests that have a given form post key / values
;;Fetch requests that have a given XML / JSON body key (s) / value (s)
