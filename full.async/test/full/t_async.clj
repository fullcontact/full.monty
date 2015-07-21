(ns full.t-async
  (:require [midje.sweet :refer :all]
            [full.async :refer :all]
            [clojure.core.async :refer [<!! >! >!! go chan close! alt! timeout] :as async]))

(facts
  (fact
    (<!! (go (let [ch (chan 2)]
               (>! ch "1")
               (>! ch "2")
               (close! ch)
               (<<! ch))))
    => ["1" "2"])

  (fact
    (<!! (go (let [ch (chan 2)]
               (>! ch "1")
               (>! ch "2")
               (close! ch)
               (<<! ch))))
    => ["1" "2"])

  (fact
    (<?? (go-try (let [ch (chan 2)]
               (>! ch "1")
               (>! ch (Exception.))
               (close! ch)
               (<<? ch))))
    => (throws Exception))

  (fact
    (<<!! (let [ch (chan 2)]
            (>!! ch "1")
            (>!! ch "2")
            (close! ch)
            ch))
    => ["1" "2"])

  (fact
    (<!! (go (<<! (let [ch (chan 2)]
                    (>! ch "1")
                    (>! ch "2")
                    (close! ch)
                    ch))))
    => ["1" "2"])

  (fact
    (<<?? (let [ch (chan 2)]
            (>!! ch "1")
            (>!! ch (Exception.))
            (close! ch)
            ch))
    => (throws Exception))

  (fact
    (<!!* [(go "1") (go "2")])
    => ["1" "2"])

  (fact
    (<??* [(go "1") (go "2")])
    => ["1" "2"])

  (fact
    (<??* (list (go "1") (go "2")))
    => ["1" "2"])

  (fact
    (<??* [(go "1") (go (Exception. ))])
    => (throws Exception))

  (fact
    (->> (let [ch (chan)]
           (go (doto ch (>!! 1) (>!! 2) close!))
           ch)
         (pmap>> #(go (inc %)) 2)
         (<<??)
         (set))
    => #{2 3})

  (fact
    (let [ch1 (chan)
          ch2 (chan)]
      (go (doto ch2 (>!! 3) (>!! 4) close!))
      (go (doto ch1 (>!! 1) (>!! 2) close!))
      (<<?? (concat>> ch1 ch2))
      => [1 2 3 4]))

  (fact
    (->> (let [ch (chan)]
           (go (doto ch (>!! 1)
                        (>!! 2)
                        (>!! 3)
                        close!))
           ch)
         (partition-all>> 2)
         (<<??))
    => [[1 2] [3]])

  (fact
    (try<??
      (go-try (throw (Exception.)))
      false
      (catch Exception _
        true))
    => true))

;; go-try
(fact
 (<?? (go-try (alt? (timeout 100) 43
                    :default (ex-info "foo" {}))))
 => (throws Exception))

;; thread-try
(fact
 (<?? (thread-try 42))
 => 42)

(fact
 (<?? (thread-try (throw (ex-info "bar" {}))))
 => (throws Exception))

;; thread-super
(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->RestartingSupervisor {:error err-ch :abort abort
                                         :registered (atom {})})]
   (<?? (thread-super super 42))) => 42)

(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->RestartingSupervisor {:error err-ch :abort abort
                                         :registered (atom {})})]
   (thread-super super
                 (/ 1 0))
   (<?? err-ch)
   => (throws Exception)))

;; go-loop-try
(fact
 (<?? (go-loop-try [[f & r] [1 0]]
                   (/ 1 f)
                   (recur r)))
 => (throws Exception))

;; go-try error channel
(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->RestartingSupervisor {:error err-ch :abort abort
                                         :registered (atom {})})]
   (go-super super
             (/ 1 0))
   (<?? err-ch)
   => (throws Exception)))

;; go-loop-try error channel
(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->RestartingSupervisor {:error err-ch :abort abort
                                         :registered (atom {})})]
   (go-loop-super super
                  [[f & r] [1 0]]
                  (/ 1 f)
                  (recur r))
   (<?? err-ch)
   => (throws Exception)))


;; go-for
(fact ;; traditional for comprehension
 (<<?? (go-for [a (range 5)
                :let [c 42]
                b [5 6]
                :when (even? b)]
               [a b c]))
 => '([0 6 42] [1 6 42] [2 6 42] [3 6 42] [4 6 42]))

(fact ;; async operations spliced in bindings and body
 (<<?? (go-for [a [1 2 3]
                :let [b (<? (go (* a 2)))]]
               (<? (go [a b]))))
 => '([1 2] [2 4] [3 6]))

(fact ;; verify that nils don't prematurely terminate go-for
 (<<?? (go-for [a [1 nil 3]]
               [a a]))
 => [[1 1] [nil nil] [3 3]])


(facts ;; async operations propagate exceptions
 (<<?? (go-for [a [1 2 3]
                :let [b 0]]
               (/ a b)))
 => (throws Exception)

 (<<?? (go-for [a [1 2 3]
                :let [b (/ 1 0)]]
               42))
 => (throws Exception))


;; supervisor

(fact
 (let [start-fn (fn [super]
                  (go-super super
                            42))]
   (<?? (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))
 => 42)

(fact
 (let [start-fn (fn [super]
                  (go-super super
                            (throw (ex-info "foo" {}))))]
   (<?? (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))
 => (throws Exception))

;; fails
(fact
 (let [try-fn (fn [] (go-try (throw (ex-info "stale" {}))))
       start-fn (fn [super]
                  (go-super super
                            (try-fn) ;; should trigger restart after max 2*stale-timeout
                            42))]
   (<?? (restarting-supervisor start-fn :retries 3 :stale-timeout 10)))
 => (throws Exception))


;; a trick: test correct waiting with staleness in other part
(fact
 (let [slow-fn (fn [super]
                 (go-super super
                           (try
                             (<? (timeout 500))
                             (catch Exception e
                               #_(println "Aborted by:" (.getMessage e)))
                             (finally
                               (<? (timeout 100))
                               #_(println "Cleaned up slowly.")))))
       try-fn (fn [] (go-try (throw (ex-info "stale" {}))))

       start-fn (fn [super]
                  (go-super super
                            (try-fn) ;; should trigger restart after max 2*stale-timeout
                            (slow-fn super) ;; concurrent part which needs to free resources
                            42))]
   (<?? (restarting-supervisor start-fn :retries 3 :stale-timeout 100)))
 => (throws Exception))


;; transducer embedding

(fact
 (let [err-ch (chan)
       abort (chan)
       super (map->RestartingSupervisor {:error err-ch :abort abort
                                         :registered (atom {})})]

   (go-super super
             (let [ch (chan-super 10 (comp (map (fn [b] (/ 1 b)))
                                           ;; wrong comp order causes division by zero
                                           (filter pos?)))]
               (async/onto-chan ch [1 0 3])))
   (<?? err-ch)
   => (throws Exception)))
