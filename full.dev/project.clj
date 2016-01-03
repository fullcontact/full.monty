(defproject fullcontact/full.dev "0.9.1-SNAPSHOT"
  :description "Clojure's development and debugging helpers"

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [ns-tracker "0.2.2"]
                 [fullcontact/full.core "0.9.1-SNAPSHOT"]]

  :aot :all

  :plugins [[lein-midje "3.1.3"]]

  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
