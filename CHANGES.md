# Breaking changes in 0.10

All projects have been moved to separate repositories

* full.core
  * removed config loader macros `defconfig`, `defoptconfig`, and `defmappedconfig`
  * only `$FULL_CONFIG` is being checked for config file path
  * removed `transf` - use `clojure.core/update`
  * removed `?transf` - use  `full.core.sugar/?update`
  * `uuid` is now `uuids`

* full.dev
  * added `do-bell` macro
  * moved to `full.core` as `full.core.dev`

* full.edn
  * moved to `full.core` as `full.core.edn`

* full.json
  * add `slurp-json-resource`
  * proxy Cheshire's `pretty` flag for JSON generation

* full.http
  * `application/json` request header is used as a fallback header when JSON
    encoding for request body takes place
  * requests use `POST` as default method when json body is present
  * removed HTTP 599 error in favor of HTTP 500 and HTTP 503
  * request returns a promise channel by default & accepts a custom output
    channel.

* full.liquibase
  * moved to `full.db` as `full.db.liquibase`

* full.rollbar
  * moved to `full.metrics`; no API changes
