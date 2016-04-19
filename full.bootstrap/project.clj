(defproject fullcontact/full.bootstrap "0.9.1-SNAPSHOT"
  :description "Boostrap module that pulls in all commonly used full-monty dependencies."

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [fullcontact/full.core "0.9.1-SNAPSHOT"]
                 [fullcontact/full.time "0.9.1-SNAPSHOT"]
                 [fullcontact/camelsnake "0.9.1-SNAPSHOT"]
                 [fullcontact/full.json "0.9.1-SNAPSHOT"]
                 [fullcontact/full.async "0.9.0"]
                 [fullcontact/full.dev "0.9.1-SNAPSHOT"]
                 [fullcontact/full.cache "0.9.1-SNAPSHOT"]
                 [fullcontact/full.metrics "0.9.1-SNAPSHOT"]
                 [fullcontact/full.http "0.9.1-SNAPSHOT"]]

  :aot :all

  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
