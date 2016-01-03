(defproject fullcontact/full.http "0.9.1-SNAPSHOT"
  :description "Async HTTP client and server on top of http-kit and core.async."

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [fullcontact/http-kit "2.1.20"]
                 [compojure "1.3.4" :exclusions [clj-time]]
                 [javax.servlet/servlet-api "2.5"]
                 [ring-cors "0.1.7"]
                 [fullcontact/camelsnake "0.9.1-SNAPSHOT"]
                 [fullcontact/full.json "0.9.1-SNAPSHOT"]
                 [fullcontact/full.metrics "0.9.1-SNAPSHOT"]
                 [fullcontact/full.async "0.9.1-SNAPSHOT"]
                 [fullcontact/full.core "0.9.1-SNAPSHOT"]]

  :aot :all

  :plugins [[lein-midje "3.1.3"]]

  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
