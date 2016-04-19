(defproject fullcontact/full.cache "0.9.1-SNAPSHOT"
  :description "In-memory + memcache caching for Clojure with async loading."

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [net.jodah/expiringmap "0.4.1"]
                 [net.spy/spymemcached "2.12.0"]
                 [com.taoensso/nippy "2.9.0"]
                 [fullcontact/full.core "0.9.1-SNAPSHOT"]
                 [fullcontact/full.async "0.9.0"]]

  :aot :all

  :plugins [[lein-midje "3.1.3"]]

  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
