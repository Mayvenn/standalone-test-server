(ns standalone-test-server.query
  (:require [clojure.set :as set]
            [clojure.data.json :as json]
            [ring.util.codec :as ring]))

(def json-parser json/read-str)
(def form-parser ring/form-decode)
(defn xml-parser [] (throw (Exception. "XML Parsing has not been implmented")))

(def content-type->parser
  "Maps a content type to a parsing function of the form String => map"
  {"application/json" json-parser
   "application/xml" xml-parser
   "text/xml" xml-parser
   "application/x-www-form-urlencoded" form-parser
   nil {}})

(defn ^:private compare-in [m keypath comp-fn val]
  (comp-fn val (get m keypath)))

(defn with-uri
  "A filter function which will remove requests
  where its :uri does not equal uri"
  [uri col]
  (filter #(= uri (:uri %)) col))

(defn with-method
  "A filter function which will remove requests
  where its :request-method does not equal method"
  [method col]
  {:pre [(keyword? method)]}
  (filter #(= method (:request-method %))
          col))

(defn with-query-keys
  "A filter function which will remove requests
  where its parsed :query-string's keys do not match key-set."
  [key-set col]
  {:pre [(set? key-set)]}
  (filter #(= key-set
              (set (keys (form-parser (:query-string %)))))
          col))

(defn with-query-key-subset
  "A filter function which will remove requests
  where key-set is a not subset of the parsed :query-string's keys."
  [key-set col]
  {:pre [(set? key-set)]}
  (filter #(set/subset? key-set
                        (set (keys (form-parser (:query-string %)))))
          col))

(defn with-query-params
  "A filter function which will remove requests
  where the parsed :query-string does not match kv-map."
  [kv-map col]
  (filter #(= kv-map (form-parser (:query-string %))) col))

(defn with-body-keys
  "A filter function which will remove requests
  where the parsed :body's keys do not match body-keys.

  :body is parsed by the request's content-type"
  [body-keys col]
  {:pre [(set? body-keys)]}
  (filter (fn [request]
            (when-let [parser (-> (get-in request [:headers "content-type"])
                                  content-type->parser)]
              (= body-keys
                 (set (keys (parser (:body request)))))))
          col))

(defn with-body-key-subset
  "A filter function which will remove requests
  where the body-keys is a subset of the parsed :body's keys.

  :body is parsed by the request's content-type"
  [body-keys col]
  {:pre [(set? body-keys)]}
  (filter (fn [request]
            (when-let [parser (-> (get-in request [:headers "content-type"])
                                  content-type->parser)]
              (set/subset? body-keys
                           (set (keys (parser (:body request)))))))
          col))

(defn with-body
  "A filter function which will remove requests
  where the parsed :body does not match body.

  :body is parsed by the request's content-type"
  [body col]
  (filter  (fn [request]
             (when-let [parser (-> (get-in request [:headers "content-type"])
                                   content-type->parser)]
               (= body
                  (parser (:body request)))))
           col))
