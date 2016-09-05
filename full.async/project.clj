(defproject io.replikativ/full.async "0.9.1.3-SNAPSHOT"
  :description "Extensions and helpers for core.async."

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.34"]
                 [org.clojure/core.async "0.2.374"]]

  :aot :all

  :main full.async

  :plugins [[lein-midje "3.1.3"]
            [lein-cljsbuild "1.1.2"]]

  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :figwheel {:nrepl-port 7888
                              :nrepl-middleware ["cider.nrepl/cider-middleware"
                                                 "cemerick.piggieback/wrap-cljs-repl"]}
                   :plugins [[lein-figwheel "0.5.0-2"]]}}

  :cljsbuild
  {:builds [{:id "adv"
             :source-paths ["src"]
             :compiler
             {:main full.binding_test
              :output-to "resources/public/js/client.js"
              :optimizations :advanced}}
            {:id "cljs_repl"
             :source-paths ["src"]
             :figwheel true
             :compiler
             {:main full.async
              :asset-path "js/out"
              :output-to "resources/public/js/client.js"
              :output-dir "resources/public/js/out"
              :optimizations :none
              :pretty-print true}}]})
