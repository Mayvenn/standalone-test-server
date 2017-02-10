# Standalone Test Server

[![Circle CI](https://circleci.com/gh/Mayvenn/standalone-test-server.svg?style=svg&circle-token=599f432978d381e2614f42ed892267b45dde78d9)](https://circleci.com/gh/Mayvenn/standalone-test-server) [Documentation](http://mayvenn.github.io/standalone-test-server/standalone-test-server.core.html)

A simple clojure HTTP ring handler to test HTTP requests.

Instead of mocking or stubbing HTTP requests, this library can spawn a basic web
server that runs any ring handler while recording all incoming requests.

We use it at Mayvenn to simulate external apis in our tests. We've written a
short [blog post](http://engineering.mayvenn.com/2015/06/26/Testing-External-HTTP-Requests/) to
help explain some of the motivation/reasoning behind this small library.

## Installation

Add this line to your `:dependencies` key for your project.clj:

```clojure
[standalone-test-server "0.6.1"]
```

Then you can require it using:

```clojure
(ns ...
    (:require [standalone-test-server :refer [standalone-server
                                              recording-endpoint
                                              with-standalone-server]]))
```

## Usage

There are only two functions and one macro. But they are usually used together
to form a test case.

### standalone-server

A wrapper around [ring.adapter.jetty](https://github.com/ring-clojure/ring/tree/master/ring-jetty-adapter)'s
`run-jetty` function.

Like `run-jetty`, it expects a ring handler and some (optional) config.

```clojure
(let [server (standalone-server (constantly {:status 201, :body "hi"}))]
  (try
    (http/get "http://localhost:4334/endpoint") ;; NOTE: request port must match the standalone-server's port
    (finally
      (.stop server))))
```

### with-standalone-server (macro)

You can avoid the let-try-finally boilerplate of `standalone-server` with the
`with-standalone-server` macro.

It assumes the first binding is the server:

```clojure
(with-standalone-server [server (standalone-server (constantly {:status 201, :body "hi"}))]
  ;; perform requests
  ;; macro ensures `(.stop server)`
  )
```

### recording-endpoint

`standalone-server` expects a handler. When you want to record the requests that
pass through that handler, use `recording-endpoint`.

This function wraps (or creates - see below) a ring middleware handler. It
returns a tuple: the first item is an atom containing the sequence of requests
the handler has received; the second item is a modified handler to pass to the
`standalone-server`.

```clojure
(let [[requests handler] (recording-endpoint)]
  (with-standalone-server [s (standalone-server handler)]
    (http/get "http://localhost:4334/endpoint")
    (is (= 1 (count @requests)))))
```

You can provide a `:handler` as the underlying ring handler to call. If none is
provided, it uses a default that returns a 200 empty body response.

```clojure
(let [[requests handler]
      (recording-endpoint {:handler (constantly {:status 201, :body "hi"})})]
  (with-standalone-server [s (standalone-server handler)]
    (let [response (http/get "http://localhost:4334/endpoint")]
      (is (= 1 (count @requests)))
      (is (= (:body response) "hi")))))
```

Most tests will look like this, so make sure you understand this format.

## Waiting for asynchronous requests

Many systems will make requests to the standalone server asynchronously. Tests
usually want to block until the requests have been made before making further
assertions. There are several helpers for waiting until the requests meet a
condition before continuing. They are all based on the `requests-meet?` helper.

This helper takes a requests atom and a predicate. If the requests satisfy the
predicate before the timeout this helper returns true. Otherwise, it returns
false.

```clojure
(let [[requests handler] (recording-endpoint)]
  (with-standalone-server [s (standalone-server handler)]
    ;; Trigger async code which will make request...
    (is (requests-meet? requests #(= 1 (count %)) {:timeout 1000}))
    ;; Assertions about how system has changed after receiving response...
    ))
```

A shorter way to do the above is `(is (requests-count? requests 1))`. You can
also check `(is (requests-min-count? requests 1))` if you want to wait for *at
least* one request.

Note that just because a request was recorded, doesn't
mean your system has received the response yet or even has 
had time to process it. You may still need to poll for
whether your system has successfully processed the response.

If you don't know how many requests will be made and just want to wait until the
server has stopped receiving them, use `requests-quiescent`:

```clojure
(let [[requests handler] (recording-endpoint)]
  (with-standalone-server [s (standalone-server handler)]
    ;; Trigger async code which will make unknown number of requests...
    (requests-quiescent requests {:for-ms 1000})))
```

Note that `requests-quiescent` will always take at least `for-ms` to return.
This is because it starts a timer when it is called, resets the timer after
every new request, and waits for the timer to expire before declaring
quiescence.

Because of this, your tests will be much faster if you can use
`requests-count?`.

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
