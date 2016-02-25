# Breaking changes in 0.10

* full.core
  * removed config loader macros `defconfig`, `defoptconfig`, and `defmappedconfig`
  * only `$FULL_CONFIG` is being checked for config file path

* full.dev
  * added `do-bell` macro

* full.json
  * add `slurp-json-resource`

* full.http
  * `application/json` request header is used as a fallback header when JSON
    encoding for request body takes place
  * requests use `POST` as default method when json body is present
  * removed HTTP 599 error in favor of HTTP 500 and HTTP 503
