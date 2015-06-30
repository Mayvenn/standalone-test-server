# Standalone Test Server

[![Circle CI](https://circleci.com/gh/Mayvenn/standalone-test-server.svg?style=svg&circle-token=599f432978d381e2614f42ed892267b45dde78d9)](https://circleci.com/gh/Mayvenn/standalone-test-server) [Documentation](http://mayvenn.github.io/standalone-test-server/standalone-test-server.core.html)

A simple HTTP ring handler to test HTTP requests.

Instead of mocking or stubbing HTTP requests, this library can spawn a basic web server
that run any ring handler while recording all incoming requests.

We use it at Mayvenn to write tests with a simulated http api. We've written a short [blog post](http://engineering.mayvenn.com/2015/06/26/Testing-External-HTTP-Requests/) to help explain some of the motivation/reasoning behind this small library.

## Installation

Add this line to your `:dependencies` key for your project.clj:

```clj
[standalone-test-server "0.3.0"]
```

Then you can require it using:

```clj
(ns ...
    (:require [standalone-test-server :refer [standalone-server
                                              recording-endpoint
                                              with-standalone-server]]))
```

## Usage

There are only two functions and one macro. But they are usually used together to form a test case.


### standalone-server

A wrapper around [ring.adapter.jetty](https://github.com/ring-clojure/ring/tree/master/ring-jetty-adapter)'s
run-jetty function. Simply passes through with a default port of `4334` and `:join false`.

```clj
(let [server (standalone-server)]
  (try
    ;; perform requests
    (finally
      (.stop server))))
```

Since it's common to do this pattern, you can use the `with-standalone-server` macro to avoid having to
try-finally your own code.

### with-standalone-server (macro)

This macro removes the boilerplate of let-try-finally. It assumes the first binding is the server:

```clj
(with-standalone-server [server (standalone-server)]
  ;; perform requests
  )
```


### recording-endpoint

This function is a ring middleware that records all requests that pass through it.
Unlike regular middleware, this function will return a vector of the handler and
the function you can call to retrieve the recorded requests.

```clj
(let [[retrieve-requests handler] (recording-endpoint)]
  (with-standalone-server [s (standalone-server handler)]
    (http/get "http://localhost:4334/endpoint")
    (is (= 1 (count (retrieve-requests))))))
```

By default, the recording endpoint's `retrieve-request` function will wait up to a
second for one request to be received.

There are two optional arguments:

- `:request-count` the number of requests to capture before letting the `retrieve-requests` return successfully
- `:handler` the underlying ring handler to call. If none is provided, it uses a default that returns a 200 empty body response.

You can override them by passing a map:

```clj
(let [[retrieve-requests handler]
      (recording-endpoint {:request-count 2
                           :handler (constantly {:status 201, :body "hi"})})]
  (with-standalone-server [s (standalone-server handler)]
    (is (= (:body (http/get "http://localhost:4334/endpoint"))
           "hi"))
    (is (= 1 (count (retrieve-requests))))))
```
