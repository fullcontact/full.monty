(ns full.async
  (:require [clojure.core.async :refer [<! <!! >! >!! alt! alt!!
                                        alts! alts!! go go-loop
                                        chan thread timeout put! pub sub close!]
             :as async])
  (:import (clojure.core.async.impl.protocols ReadPort)))


(defprotocol PSupervisor
  (-error [this])
  (-abort [this])
  (-register-go [this body])
  (-unregister-go [this id])
  (-track-exception [this e])
  (-free-exception [this e]))

(defrecord RestartingSupervisor [error abort registered pending-exceptions]
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


(def ^:dynamic *super* (let [err-ch (chan)
                             stale-timeout (* 60 1000) ;; be conservative for REPL
                             s (map->RestartingSupervisor {:error err-ch ;; TODO dummy supervisor
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


(defn chan-super
  "Creates a supervised channel for transducer xform. Exceptions
  immediatly propagate to the supervisor."
  [buf-or-n xform]
  (chan buf-or-n xform (fn [e] (put! (:error *super*) e))))

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
  "Similiar to dorun. Simply takes messages from channel but does
  nothing with them. Returns channel that will close when all messages
  have been consumed."
  [ch]
  (go-loop []
    (when (<! ch) (recur))))

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

;; taken from clojure/core ~ 1.7
(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                 (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
       ~(let [more (nnext pairs)]
          (when more
            (list* `assert-args more)))))


(defmacro go-for
  "List comprehension adapted from clojure.core 1.7. Takes a vector of
  one or more binding-form/collection-expr pairs, each followed by
  zero or more modifiers, and yields a channel of evaluations of
  expr. It is eager on all but the outer-most collection. TODO

  Collections are iterated in a nested fashion, rightmost fastest, and
  nested coll-exprs can refer to bindings created in prior
  binding-forms.  Supported modifiers are: :let [binding-form expr
  ...],
  :while test, :when test. If a top-level entry is nil, it is skipped
  as it cannot be put on the result channel by core.async semantics.

  (<<? (go-for [x (range 10) :let [y (<? (go-try 4))] :while (< x y)] [x y]))"
  [seq-exprs body-expr]
  (assert-args
   (vector? seq-exprs) "a vector for its binding"
   (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [to-groups (fn [seq-exprs]
                    (reduce (fn [groups [k v]]
                              (if (keyword? k)
                                (conj (pop groups) (conj (peek groups) [k v]))
                                (conj groups [k v])))
                            [] (partition 2 seq-exprs)))
        err (fn [& msg] (throw (IllegalArgumentException. ^String (apply str msg))))
        emit-bind (fn emit-bind [res-ch [[bind expr & mod-pairs]
                                        & [[_ next-expr] :as next-groups]]]
                    (let [giter (gensym "iter__")
                          gxs (gensym "s__")
                          do-mod (fn do-mod [[[k v :as pair] & etc]]
                                   (cond
                                     (= k :let) `(let ~v ~(do-mod etc))
                                     (= k :while) `(when ~v ~(do-mod etc))
                                     (= k :when) `(if ~v
                                                    ~(do-mod etc)
                                                    (recur (rest ~gxs)))
                                     (keyword? k) (err "Invalid 'for' keyword " k)
                                     next-groups
                                     `(let [iterys# ~(emit-bind res-ch next-groups)
                                            fs# (<? (iterys# ~next-expr))]
                                        (if fs#
                                          (concat fs# (<? (~giter (rest ~gxs))))
                                          (recur (rest ~gxs))))
                                     :else `(let [res# ~body-expr]
                                              (when res# (>! ~res-ch res#))
                                              (<? (~giter (rest ~gxs))))
                                     #_`(cons ~body-expr (<? (~giter (rest ~gxs))))))]
                      `(fn ~giter [~gxs]
                         (go-loop-try [~gxs ~gxs]
                                      (let [~gxs (seq ~gxs)]
                                        (when-first [~bind ~gxs]
                                          ~(do-mod mod-pairs)))))))
        res-ch (gensym "res_ch__")]
    `(let [~res-ch (chan)
           iter# ~(emit-bind res-ch (to-groups seq-exprs))]
       (go (try (<? (iter# ~(second seq-exprs)))
                (catch Exception e#
                  (-track-exception *super* e#)
                  (>! ~res-ch e#))
                (finally (async/close! ~res-ch))))
       ~res-ch)))


(defn restarting-supervisor
  "Starts a subsystem with supervised go-routines initialized by
  start-fn. Restarts the system on error. All blocking channel ops in
  the subroutines (supervised context) are aborted with an exception
  on error to force total termination. The supervisor waits until all
  supervised go-routines are finished and have freed resources before
  restarting.

  If exceptions are not taken from go-try channels (by error), they
  become stale after stale-timeout and trigger a restart. "
  [start-fn & {:keys [retries delay stale-timeout]
               :or {retries 5
                    delay 0
                    stale-timeout (* 60 1000)}}]
  (let [s (map->RestartingSupervisor {:error (chan) :abort (chan)
                                      :registered (atom {})
                                      :pending-exceptions (atom {})})]
    (go-loop-try [i retries]
                 (let [err-ch (chan)
                       ab-ch (chan)
                       close-ch (chan)
                       s (assoc s
                                :error err-ch :abort ab-ch
                                :pending-exceptions (atom {}))
                       res-ch (start-fn s)]

                   (go-loop [] ;; todo terminate loop
                     (<! (timeout stale-timeout))
                     (let [[[e _]] (filter (fn [[k v]]
                                             (> (- (.getTime (java.util.Date.)) stale-timeout)
                                                (.getTime v)))
                                           @(:pending-exceptions s))]
                       (if e
                         (do
                           #_(println "STALE Error in supervisor:" e)
                           (-free-exception s e)
                           (>! err-ch e))
                         (recur))))

                   (go-loop []
                     (if-not (and (empty? @(:registered s))
                                  (empty? @(:pending-exceptions s)))
                       (do
                         #_(println "waiting for go-routines: " @(:registered s))
                         (<! (timeout 100))
                         (recur))
                       (close! close-ch)))


                   (let [[e? c] (alts! [err-ch close-ch] :priority true)]
                     (if-not (= c close-ch) ;; an error occured
                       (do
                         (close! err-ch)
                         (close! ab-ch)
                         (<! close-ch) ;; wait until we are finished
                         (if-not (pos? i)
                           (throw e?)
                           (do (<! (timeout delay))
                               (recur (dec i)))))
                       (<? res-ch)))))))


(comment
  (<?? (go-try (<? (go 42)) (throw (ex-info "foo" {}))))

  (go-try (throw (ex-info "foo" {})))

  (let [slow-fn (fn [super]
                  (go-super super
                            (try
                              (<? (timeout 5000))
                              (catch Exception e
                                (println e))
                              (finally
                                (<! (timeout 1000))
                                (println "Cleaned up slowly.")))))
        try-fn (fn [] (go-try (throw (ex-info "stale" {}))))
        database-lookup (fn [key] (go-try (vec (repeat 3 (inc key)))))

        start-fn (fn [super]
                   (go-super super
                             (try-fn) ;; should trigger restart after max 2*stale-timeout
                             #_(slow-fn super) ;; concurrent part which needs to free resources

                             ;; transducer exception handling
                             #_(let [ch (chan-super 10 (comp (map (fn [b] (/ 1 b)))
                                                             (filter pos?)))]
                                 (async/onto-chan ch [1 0 3]))

                             ;; go-for for complex control flow with
                             ;; blocking (read) ops (avoiding function
                             ;; boundary core.async boilerplate)
                             #_(println (<<? (go-for [a [1 2 #_nil -3] ;; comment out nil => BOOOM
                                                      :let [[b] (<? (database-lookup a))]
                                                      :when (even? b)
                                                      c (<? (database-lookup b))]
                                                     [a b c])))
                             (<? (timeout 100))
                             #_(throw (ex-info "foo" {}))
                             3))]
    (<?? (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))

  (go-try (throw (ex-info "fooz" {})))

  (go-super *super*
            (throw (ex-info "barz" {})))

  )
