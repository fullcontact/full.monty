(ns full.async
  (:require [clojure.core.async :refer [<! <!! >! >!! alt! alt!!
                                        alts! alts!! go go-loop
                                        chan thread timeout put! pub sub close!]
             :as async])
  (:import (clojure.core.async.impl.protocols ReadPort)))


;; needed for the channel ops to be transparent to for supervision,
;; most importantly exception tracking
(defprotocol PSupervisor
  (-error [this])
  (-abort [this])
  (-register-go [this body])
  (-unregister-go [this id])
  (-track-exception [this e])
  (-free-exception [this e]))

(defrecord TrackingSupervisor [error abort registered pending-exceptions]
  PSupervisor
  (-error [this] error)
  (-abort [this] abort)
  (-register-go [this body]
    (let [id (java.util.UUID/randomUUID)]
      (swap! registered assoc id body)
      id))
  (-unregister-go [this id]
    (swap! registered dissoc id))
  (-track-exception [this e]
    (swap! pending-exceptions assoc e (java.util.Date.)))
  (-free-exception [this e]
    (swap! pending-exceptions dissoc e)))


(def ^:dynamic *super*
  ;; implement a printing supervisor for the REPL and as fallback,
  ;; this can be improved, e.g. by using a logger instead std out
  (let [err-ch (chan)
        stale-timeout (* 60 1000) ;; be conservative for REPL
        s (map->TrackingSupervisor {:error err-ch
                                    :abort (chan)
                                    :registered (atom {})
                                    :pending-exceptions (atom {})})]
    (go-loop [e (<! err-ch)]
      (<! (timeout 100)) ;; do not turn crazy
      (println "Global supervisor:" e)
      (recur (<! err-ch)))

    (go-loop [] ;; todo terminate loop
      (<! (timeout stale-timeout))
      (let [[[e _]] (filter (fn [[k v]]
                              (> (- (.getTime (java.util.Date.)) stale-timeout)
                                 (.getTime v)))
                            @(:pending-exceptions s))]
        (if e
          (do
            (println "Global supervisor detected stale error:" e)
            (-free-exception s e))
          (recur))))

    s))


(defn throw-if-exception
  "Helper method that checks if x is Exception and if yes, wraps it in a new
  exception, passing though ex-data if any, and throws it. The wrapping is done
  to maintain a full stack trace when jumping between multiple contexts."
  [x]
  (if (instance? Exception x)
    (do (-free-exception *super* x)
        (throw (ex-info (or (.getMessage x) (str x))
                        (or (ex-data x) {})
                        x)))
    x))

(defmacro go-try
  "Asynchronously executes the body in a go block. Returns a channel
  which will receive the result of the body when completed or the
  exception if an exception is thrown. You are responsible to take
  this exception and deal with it! This means you need to take the
  result from the cannel at some point."
  [& body]
  `(let [id# (-register-go *super* (quote ~body))]
     (go
       (try
         ~@body
         (catch Exception e#
           (-track-exception *super* e#)
           e#)
         (finally
           (-unregister-go *super* id#))))))

(defmacro go-super
  "Asynchronously executes the body in a go block. Returns a channel which
  will receive the result of the body when completed or nil if an
  exception is thrown. Communicates exceptions via supervisor channels."
  [super & body]
  `(let [id# (-register-go ~super (quote ~body))]
     (go
       (try
         (binding [*super* ~super]
           ~@body)
         (catch Exception e#
           ;; bug in core.async:
           ;; No method in multimethod '-item-to-ssa' for dispatch value: :protocol-invoke
           (let [err-ch# (-error ~super)]
             (>! err-ch# e#)))
         (finally
           (-unregister-go ~super id#))))))

(defmacro go-loop-try [bindings & body]
  `(go-try (loop ~bindings ~@body)))

(defmacro go-loop-super [super bindings & body]
  `(go-super ~super (loop ~bindings ~@body)))

(defmacro thread-try
  "Asynchronously executes the body in a thread. Returns a channel
  which will receive the result of the body or the exception if one is
  thrown. "
  [& body]
  `(let [id# (-register-go *super* (quote ~body))]
     (thread
       (try
         ~@body
         (catch Exception e#
           (-track-exception *super* e#)
           e#)
         (finally
           (-unregister-go *super* id#))))))

(defmacro thread-super
  "Asynchronously executes the body in a thread. Returns a channel
  which will receive the result of the body when completed or nil if
  an exception is thrown. Communicates exceptions via supervisor
  channels."
  [super & body]
  `(let [id# (-register-go ~super (quote ~body))]
     (thread
       (try
         (binding [*super* ~super]
           ~@body)
         (catch Exception e#
           ;; bug in core.async:
           ;; No method in multimethod '-item-to-ssa' for dispatch value: :protocol-invoke
           (let [err-ch# (-error ~super)]
             (put! err-ch# e#)))
         (finally
           (-unregister-go ~super id#))))))

(defmacro go-retry
  [{:keys [exception retries delay error-fn]
    :or {error-fn nil, exception Exception, retries 5, delay 1}} & body]
  `(let [error-fn# ~error-fn]
     (go-loop
       [retries# ~retries]
       (let [res# (try ~@body (catch Exception e# e#))]
         (if (and (instance? ~exception res#)
                  (or (not error-fn#) (error-fn# res#))
                  (> retries# 0))
           (do
             (<? (async/timeout (* ~delay 1000)))
             (recur (dec retries#)))
           res#)))))



(defmacro wrap-abort!
  "Internal."
  [& body]
  `(let [abort# (-abort *super*)
         [val# port#] (alts! [abort# (timeout 0)] :priority true)]
     (if (= port# abort#)
       (ex-info "Aborted operations" {:type :aborted})
       (do ~@body))))

(defmacro <?
  "Same as core.async <! but throws an exception if the channel returns a
  throwable object or the context has been aborted."
  [ch]
  `(throw-if-exception
    (let [abort# (-abort *super*)
          [val# port#] (alts! [abort# ~ch] :priority :true)]
      (if (= port# abort#)
        (ex-info "Aborted operations" {:type :aborted})
        val#))))


(defn <??
  "Same as core.async <!! but throws an exception if the channel returns a
  throwable object or the context has been aborted. "
  [ch]
  (throw-if-exception
   (let [abort (-abort *super*)
         [val port] (alts!! [abort ch] :priority :true)]
     (if (= port abort)
       (ex-info "Aborted operations" {:type :aborted})
       val))))


(defmacro try<?
  [ch & body]
  `(try (<? ~ch) ~@body))

(defmacro try<??
  [ch & body]
  `(try (<?? ~ch) ~@body))



(defn take?
  "Same as core.async/take!, but tracks exceptions in supervisor. TODO
  deal with abortion."
  ([port fn1] (take? port fn1 true))
  ([port fn1 on-caller?]
   (async/take! port
                (fn [v]
                  (when (instance? Exception v)
                    (-free-exception *super* v))
                  (fn1 v))
                on-caller?)))


(defmacro >?
  "Same as core.async >? but throws an exception if the context has been aborted."
  [ch m]
  `(throw-if-exception (wrap-abort! (>! ~ch ~m))))

(defn put?
  "Same as core.async/put!, but tracks exceptions in supervisor. TODO
  deal with abortion."
  ([port val]
   (put? port val (fn noop [_])))
  ([port val fn1]
   (put? port val fn1 true))
  ([port val fn1 on-caller?]
   (async/put! port
               val
               (fn [ret]
                 (when (instance? Exception val)
                   (-track-exception *super* val))
                 (fn1 ret))
               on-caller?)))

(defmacro alts?
  "Same as core.async alts! but throws an exception if the channel returns a
  throwable object or the context has been aborted."
  [ports & opts]
  `(throw-if-exception
    (wrap-abort!
     (let [[val# port#] (alts! ~ports ~@opts)]
       [(throw-if-exception val#) port#]))))


(defmacro alt?
  "Same as core.async alt! but throws an exception if the channel returns a
  throwable object or the context has been aborted."
  [& clauses]
  `(throw-if-exception (wrap-abort! (alt! ~@clauses))))

(defmacro <<!
  "Takes multiple results from a channel and returns them as a vector.
  The input channel must be closed."
  [ch]
  `(let [ch# ~ch]
     (<! (async/into [] ch#))))

(defmacro <<?
  "Takes multiple results from a channel and returns them as a vector.
  Throws if any result is an exception or the context has been aborted."
  [ch]
  `(alt! (-abort *super*)
         ([v#] (throw (ex-info "Aborted operations" {:type :aborted})))

         (go (<<! ~ch))
         ([v#] (doall (map throw-if-exception v#)))))

;; TODO lazy-seq vs. full vector in <<! ?
(defn <<!!
  [ch]
  (lazy-seq
    (let [next (<!! ch)]
      (when next
            (cons next (<<!! ch))))))

(defn <<??
  [ch]
  (lazy-seq
   (let [next (<?? ch)]
     (when next
       (cons next (<<?? ch))))))

(defmacro <!*
  "Takes one result from each channel and returns them as a collection.
      The results maintain the order of channels."
  [chs]
  `(let [chs# ~chs]
     (loop [chs# chs#
            results# (clojure.lang.PersistentQueue/EMPTY)]
       (if-let [head# (first chs#)]
         (->> (<! head#)
              (conj results#)
              (recur (rest chs#)))
         (vec results#)))))

(defmacro <?*
  "Takes one result from each channel and returns them as a collection.
      The results maintain the order of channels. Throws if any of the
      channels returns an exception."
  [chs]
  `(let [chs# ~chs]
     (loop [chs# chs#
            results# (clojure.lang.PersistentQueue/EMPTY)]
       (if-let [head# (first chs#)]
         (->> (<? head#)
              (conj results#)
              (recur (rest chs#)))
         (vec results#)))))

(defn <!!*
  [chs]
  (<!! (go (<!* chs))))

(defn <??*
  [chs]
  (<?? (go-try (<?* chs))))


(defn pmap>>
  "Takes objects from in-ch, asynchrously applies function f> (function should
  return a channel), takes the result from the returned channel and if it's
  truthy, puts it in the out-ch. Returns the closed out-ch. Closes the
  returned channel when the input channel has been completely consumed and all
  objects have been processed.
  If out-ch is not provided, an unbuffered one will be used."
  ([f> parallelism in-ch]
   (pmap>> f> parallelism (async/chan) in-ch))
  ([f> parallelism out-ch in-ch]
   {:pre [(fn? f>)
          (and (integer? parallelism) (pos? parallelism))
          (instance? ReadPort in-ch)]}
   (let [threads (atom parallelism)]
     (dotimes [_ parallelism]
       (go
         (loop []
           (when-let [obj (<! in-ch)]
             (if (instance? Throwable obj)
               (do
                 (>? out-ch obj)
                 (async/close! out-ch))
               (do
                 (when-let [result (<! (f> obj))]
                   (>? out-ch result))
                 (recur)))))
         (when (zero? (swap! threads dec))
           (async/close! out-ch))))
     out-ch)))


(defn engulf
  "Similiar to dorun. Simply takes messages from channels but does nothing with
  them. Returns channel that will close when all messages have been consumed."
  [& cs]
  (let [ch (async/merge cs)]
    (go-loop []
      (when (<! ch) (recur)))))

(defn reduce>
  "Performs a reduce on objects from ch with the function f> (which
  should return a channel). Returns a channel with the resulting
  value."
  [f> acc ch]
  (let [result (chan)]
    (go-loop [acc acc]
      (if-let [x (<! ch)]
        (if (instance? Exception x)
          (do
            (>? result x)
            (async/close! result))
          (->> (f> acc x) <! recur))
        (do
          (>? result acc)
          (async/close! result))))
    result))

(defn concat>>
  "Concatenates two or more channels. First takes all values from first channel
  and supplies to output channel, then takes all values from second channel and
  so on. Similiar to core.async/merge but maintains the order of values."
  [& cs]
  (let [out (chan)]
    (go
      (loop [cs cs]
        (if-let [c (first cs)]
          (if-let [v (<! c)]
            (do
              (>! out v)
              (recur cs))
            ; channel empty - move to next channel
            (recur (rest cs)))
          ; no more channels remaining - close output channel
          (async/close! out))))
    out))


(defn partition-all>> [n in-ch & {:keys [out-ch]}]
  "Batches results from input channel into vectors of n size and supplies
  them to ouput channel. If any input result is an exception, it is put onto
  output channel directly and ouput channel is closed."
  {:pre [(pos? n)]}
  (let [out-ch (or out-ch (chan))]
    (go-loop [batch []]
      (if-let [obj (<! in-ch)]
        (if (instance? Exception obj)
          ; exception - put on output and close
          (do (>! out-ch obj)
              (async/close! out-ch))
          ; add object to current batch
          (let [new-batch (conj batch obj)]
            (if (= n (count new-batch))
              ; batch size reached - put batch on output and start a new one
              (do
                (>! out-ch new-batch)
                (recur []))
              ; process next object
              (recur new-batch))))
        ; no more results - put outstanding batch onto output and close
        (do
          (when (not-empty batch)
            (>! out-ch batch))
          (async/close! out-ch))))
    out-ch))

(defn count>
  "Counts items in a channel. Returns a channel with the item count."
  [ch]
  (async/reduce (fn [acc _] (inc acc)) 0 ch))
