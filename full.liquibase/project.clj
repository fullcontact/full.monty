(defproject fullcontact/full.liquibase "0.9.1-SNAPSHOT"
  :description "Liquibase database schema creation/upgrading."

  :url "https://github.com/fullcontact/full.monty"

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jdbc "0.3.6"]
                 [org.liquibase/liquibase-core "3.3.5"]
                 [com.mattbertolini/liquibase-slf4j "1.2.1"
                  :exclusions [org.slf4j/slf4j-api
                               org.yaml/snakeyaml]]
                 [fullcontact/full.core "0.9.1-SNAPSHOT"]]

  :aot :all

  :plugins [[lein-midje "3.1.3"]]

  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
