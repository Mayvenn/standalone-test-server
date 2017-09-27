# Standalone Test Server

[![Circle CI](https://circleci.com/gh/Mayvenn/standalone-test-server.svg?style=svg&circle-token=599f432978d381e2614f42ed892267b45dde78d9)](https://circleci.com/gh/Mayvenn/standalone-test-server) [Documentation](http://mayvenn.github.io/standalone-test-server/standalone-test-server.core.html) [![Clojars Project](https://img.shields.io/clojars/v/standalone-test-server.svg)](https://clojars.org/standalone-test-server)

A simple clojure HTTP ring handler to test HTTP requests.

Instead of mocking or stubbing HTTP requests, this library can spawn a basic web
server that runs any ring handler while recording all incoming requests.

We use it at Mayvenn to simulate external apis in our tests. We've written a
short [blog post](http://engineering.mayvenn.com/2015/06/26/Testing-External-HTTP-Requests/) to
help explain some of the motivation/reasoning behind this small library.

## Installation

Add this line to your `:dependencies` key for your project.clj:

```clojure
[standalone-test-server "0.7.3"]
```

Then you can require it using:

```clojure
(ns my-system.tests
  (:require [standalone-test-server.core :as sts]))
```

## Usage

There are only two functions and one macro. But they are usually used together
to form a test case.

### standalone-server

A wrapper around [ring.adapter.jetty](https://github.com/ring-clojure/ring/tree/master/ring-jetty-adapter)'s
`run-jetty` function.

Like `run-jetty`, it expects a ring handler and some (optional) config.

```clojure
(let [server (sts/standalone-server (constantly {:status 201, :body "hi"})
                                    {:port 4334})]
  (try
    ;; Make requests which need an HTTP server listening at a specific port
    (http/get "http://localhost:4334/endpoint")
    (finally
      (.stop server))))
```

### with-standalone-server (macro)

You can avoid the let-try-finally boilerplate of `standalone-server` with the
`with-standalone-server` macro.

It assumes the first binding is the server:

```clojure
(sts/with-standalone-server [server (sts/standalone-server (constantly {:status 201, :body "hi"}))]
  ;; perform requests
  ;; macro ensures `(.stop server)`
  )
```

### with-requests-chan

When you want to record the requests that pass through a `standalone-server`,
use `with-requests-chan`.

This function creates (or wraps - see below) a ring middleware handler. It
returns a tuple: the first item is a channel containing the requests the handler
receives; the second item is a modified handler to pass to the
`standalone-server`.

```clojure
(let [[requests handler] (sts/with-requests-chan)]
  (sts/with-standalone-server [s (sts/standalone-server handler)]
    (http/get "http://localhost:4334/endpoint")
    (is (core.async/<!! requests))))
```

By default `with-requests-chan` uses a handler that returns a 200 empty body
response. Alternatively, provide a `handler` as the underlying ring handler to
call.

```clojure
(let [[requests handler] (sts/with-requests-chan (constantly {:status 201, :body "hi"}))]
  (sts/with-standalone-server [s (sts/standalone-server handler)]
    (http/get "http://localhost:4334/endpoint")
    (is (= "hi" (:body (core.async/<!! requests))))))
```

## Waiting for asynchronous requests

Many systems will make requests to the standalone server asynchronously. Tests
usually want to wait until the requests have been made before making further
assertions. Often the tests want to make assertions about the requests
themselves, or some subset of the requests. If the system fails to produce the
expected requests, the tests should not block forever.

For these scenarios, `txfm-requests` gathers and returns the asynchronous requests.

It takes a requests channel, a filter (a transducing function) and a timeout. If
the requests satisfy the filter before the timeout this helper returns the
requests. Otherwise, it returns as many matching requests as it has received so
far.

```clojure
(let [[requests handler] (sts/with-requests-chan)]
  (sts/with-standalone-server [s (sts/standalone-server handler)]
    ;; Trigger async code which will make requests...
    (future (http/get "http://localhost:4334/endpoint1"))
    (future (http/get "http://localhost:4334/endpoint2"))
    (is (= "endpoint2"
           (-> requests
               (sts/txfm-requests (comp (filter #(= "endpoint2" (:uri %)))
                                        (take 1))
                                  {:timeout 1000})
               first
               :uri)))))
```

Most tests will look like this, so make sure you understand this format.

The filter should contain `(take n)` to avoid waiting for the whole timeout.
With a limit like this, `txfm-requests` will return as soon as `n` matching
requests have been found.

To avoid delays, mosts tests should include `(take n)`. One exception is if you
don't know how many requests will be made. In this case simply exclude the
`take`. When the timeout is reached, you will see all the requests made so far.

```clojure
(let [[requests handler] (sts/with-requests-chan)]
  (sts/with-standalone-server [s (sts/standalone-server handler)]
    ;; Trigger async code which will make unknown number of requests...
    ;; Following waits for one second, gathering as many requests as happen in
    ;; that period.
    (is (< 0 (count (sts/txfm-requests requests conj {:timeout 1000}))))))
```

A shorter way to extract the first matching request is with `txfm-request`. This
helper adds an implicit `(take 1)` to ensure it returns as quickly as possible.

```clojure
(is (= "endpoint2"
       (-> requests
           (sts/txfm-request (filter #(= "endpoint2" (:uri %)))
                             {:timeout 1000})
           :uri)))
```

Note that just because a request was recorded, doesn't mean your system has
received the response yet or even has had time to process it. You may still need
to poll for whether your system has successfully processed the response.

## Filtering requests

The `query` namespace contains helpers for filtering collections of requests.

| Name                  | Params       | Includes                                                      |
| --------------------- | ------------ | ------------------------------------------------------------- |
| with-uri              | uri coll     | Filters coll to requests with the given uri                   |
| with-method           | method coll  | With the given request method                                 |
| with-query-keys       | key-set coll | Matches key-set to the parsed query-string's keys             |
| with-query-key-subset | key-set coll | Where key-set is a subset of the parsed query-string's keys   |
| with-query-params     | kv-map coll  | Matches kv-map to the parsed query-string                     |
| with-body-keys        | key-set coll | Matches key-set to the parsed body's keys                     |
| with-body-key-subset  | key-set coll | Where key-set is a subset of the parsed body keys             |
| with-body             | kv-map coll  | Matches kv-map to the parsed body                             |
