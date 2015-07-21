# full.async

A Clojure library that extends [core.async](https://github.com/clojure/core.async)
with a number of convenience methods.

[![Clojars Project](http://clojars.org/fullcontact/full.async/latest-version.svg)](http://clojars.org/fullcontact/full.async)

## Exception Handling

Exception handling is an area for which core.async doesn't have extensive
support out of the box. If something within a `go` block throws an exception, it
will be logged but the block will simply return `nil`, hiding any information
about the actual exception. You could wrap the body of each `go` block within an
exception handler but that's  not very convenient. `full.async` provides a set of
helper functions and macros for dealing with exceptions in a simple manner:

* `go-try`: equivalent of `go` but catches any exceptions thrown and returns via
the resulting channel
* `<?`, `alts?`, `<??`: equivalents of `<!`, `alts!` and `<!!` but if the value
is an exception, it will get thrown

## Supervision *experimental*

`go-try` and the `?` operators work well when you have a sequential
execution of goroutines because you can just rethrow the exceptions on
the higher levels of the call-stack. This fails for concurrent
goroutines like go-loops or background tasks. For this purpose we have
introduced an Erlang inspired supervision concept.

Two requirements for robust systems in Erlang are:

1. All exceptions in distributed processes are caught in unifying supervision context
2. All concurrently acquired resources are freed on an exception

We decided to form a supervision context in which exceptions thrown in
concurrent parts get reported to a supervisor:

```clojure
(let [try-fn (fn [] (go-try (throw (ex-info "stale" {}))))
      start-fn (fn [super]
                 (go-super super
                           (try-fn) ;; triggers restart after stale-timeout
                           42))]
  (<?? (restarting-supervisor start-fn :retries 3 :stale-timeout 1000)))
```

The supervisor tracks all nested goroutines and waits until all are
finished and have hence freed all resources before it tries to restart
or finally returns itself either the result or the exception. This
allows composition of supervised contexts.

To really free resources you need to use the typical stack-based
try-catch-finally mechanism. To support this, blocking operations
`<?`, `>?`, `alt?`...  trigger an "abort" exception when the
supervisor detects an exception somewhere. This guarantees the termination
of all blocking contexts, since we cannot use preemption on errors like
the Erlang VM does.

The supervisor also tracks all thrown exceptions, so whenever they are
not taken off some channel and become stale, it will timeout and
restart.

## Retry Logic

Sometimes it may be necessary to retry certain things when they fail,
especially in distributed systems. The `go-retry` macro lets you achieve that,
for example:

```clojure
(go-retry {:error-fn (fn [ex] (= 409 (.getStatus ex)))
           :exception HTTPException
           :retries 3
           :delay 5}
  (make-some-http-request))
```

The above method will invoke `make-some-http-request` in a `go` block. If it
throws an exception of type `HTTPException` with a status code 409, `go-retry`
will wait 5 seconds and try invoking `make-some-http-request` again. If it still
fails after 3 attempts or a different type of exception is thrown, it will get
returned via the result channel.

## Sequences & Collections

Channels by themselves are quite similar to sequences however converting between
them may sometimes be cumbersome. `full.async` provides a set of convenience
methods for this:

* `<<!`, `<<?`: takes all items from the channel and returns them as a collection.
Must be used within a `go` block.
* `<<!!`, `<<??`: takes all items from the channel and returns as a lazy
sequence. Returns immediately.
* `<!*`, `<?*`, `<!!*`, `<??*` takes one item from each input channel and
returns them in a single collection. Results have the same ordering as input
channels.

* `go-for` is an adapted for-comprehension with channels instead of
lazy-seqs. It allows complex control flow across function boundaries
where a single transduction context would be not enough. For example:

```clojure
(let [query-fn #(go (* % 2))
      goroutine #(go [%1 %2])]
     (<<?? (go-for [a [1 2 3]
                    :let [b (<? (query-fn a))]
                    :when (> b 5)]
                    (<? (goroutine a b)))))
```

Note that channel operations are side-effects, so this is best used to
realize read-only operations like queries. Nested exceptions are
propagated according to go-try semantics.

Without `go-for you needed to form some boilerplate around function
boundaries like this:

```clojure
(<<?? (->> [1 2 3]
           (map #(go [% (<? (query-fn %))]))
           async/merge
           (async/into [])
           (filter #(> (second %) 5))
           (map (apply goroutine))
           async/merge))
```

## Parallel Processing

`pmap>>` lets you apply a function to channel's output in parallel,
returning a new channel with results.


## License

Copyright (C) 2015 FullContact. Distributed under the Eclipse Public License, the same as Clojure.
