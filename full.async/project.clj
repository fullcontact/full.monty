(defproject fullcontact/full.async "0.9.1-SNAPSHOT"
  :description "Extensions and helpers for core.async."

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]]

  :aot :all

  :plugins [[lein-midje "3.1.3"]]

  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
