(ns full.lab
  "This namespace contains experimental functionality, which might or
  might not enter the stable full.async namespace."
  (:require [full.async :refer [<? >? go-try go-loop-try PSupervisor map->TrackingSupervisor
                                *super* -free-exception -track-exception
                                -register-go -unregister-go -error -abort]]
            [clojure.core.async :refer [<! <!! >! >!! alt! alt!!
                                        alts! alts!! go go-loop
                                        chan thread timeout put! pub unsub close!]
             :as async]))

(defmacro with-super
  "Run code block in scope of a supervisor."
  [super & body]
  `(binding [*super* ~super]
     ~@body))

(defmacro on-abort
  "Executes body if the supervisor aborts the context. You *need* to
  use this to free up any external resources. This is necessary,
  because our error handling is not part of the runtime which could
  free the resources for us as is the case with the Erlang VM."
  [& body]
  `(go-try
    (<! (-abort *super*))
    ~@body))

(defn tap
  "Safely managed tap. The channel is closed on abortion and all
  pending puts are flushed."
  ([mult ch]
   (tap mult ch false))
  ([mult ch close?]
   (on-abort (close! ch) (go-try (while (<! ch))))
   (async/tap mult ch close?)))

(defn sub
  "Safely managed subscription. The channel is closed on abortion and
  all pending puts are flushed."
  ([p topic ch]
   (sub p topic ch false))
  ([p topic ch close?]
   (on-abort (close! ch) (go-try (while (<! ch))))
   (async/sub p topic ch close?)))


(defmacro go-super
  "Asynchronously executes the body in a go block. Returns a channel which
  will receive the result of the body when completed or nil if an
  exception is thrown. Communicates exceptions via supervisor channels."
  [& body]
  `(let [super# *super*
         id# (-register-go super# (quote ~body))]
     (go
       (try
         (binding [*super* super#]
           ~@body)
         (catch Exception e#
           ;; bug in core.async or the analyzer:
           ;; No method in multimethod '-item-to-ssa' for dispatch value: :protocol-invoke
           (let [err-ch# (-error super#)]
             (>! err-ch# e#)))
         (finally
           (-unregister-go super# id#))))))

(defmacro go-loop-super [bindings & body]
  `(go-super (loop ~bindings ~@body)))

(defmacro thread-super
  "Asynchronously executes the body in a thread. Returns a channel
  which will receive the result of the body when completed or nil if
  an exception is thrown. Communicates exceptions via supervisor
  channels."
  [& body]
  `(let [super# *super*
         id# (-register-go super# (quote ~body))]
     (thread
       (try
         (binding [*super* super#]
           ~@body)
         (catch Exception e#
           ;; bug in core.async:
           ;; No method in multimethod '-item-to-ssa' for dispatch value: :protocol-invoke
           (let [err-ch# (-error super#)]
             (put! err-ch# e#)))
         (finally
           (-unregister-go super# id#))))))


(defn chan-super
  "Creates a supervised channel for transducer xform. Exceptions
  immediatly propagate to the supervisor."
  [buf-or-n xform]
  (chan buf-or-n xform (fn [e] (put! (:error *super*) e))))



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
  start-fn. Restarts the system on error for retries times with a
  potential delay in seconds, an optional error-fn predicate
  determining the retry and a optional filter by exception type.

  All blocking channel ops in the subroutines (supervised context) are
  aborted with an exception on error to force total termination. The
  supervisor waits until all supervised go-routines are finished and
  have freed resources before restarting.

  If exceptions are not taken from go-try channels (by error), they
  become stale after stale-timeout and trigger a restart. "
  [start-fn & {:keys [retries delay error-fn exception stale-timeout]
               :or {retries 5
                    delay 0
                    error-fn nil
                    exception Exception
                    stale-timeout (* 60 1000)}}]
  (let [s (map->TrackingSupervisor {:error (chan) :abort (chan)
                                    :registered (atom {})
                                    :pending-exceptions (atom {})})]
    (go-loop-try [retries retries]
                 (let [err-ch (chan)
                       ab-ch (chan)
                       close-ch (chan)
                       s (assoc s
                                :error err-ch :abort ab-ch
                                :pending-exceptions (atom {}))
                       res-ch (with-super s (start-fn))]

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
                         (if-not (and (instance? exception e?)
                                      (or (not error-fn) (error-fn e?))
                                      (pos? retries))
                           (throw e?)
                           (do (<! (timeout (* 1000 delay)))
                               (recur (dec retries)))))
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
