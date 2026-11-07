(ns memo-router.spotify
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)))

(def ^:private lead-pattern
  #"(?i)^(?:look into|look up|listen to|check out|search for|find)\s+")

(def ^:private kind-pattern
  #"(?i)^(?:the\s+)?(?:song|album|artist|track|band)\s+")

(def ^:private by-pattern #"(?i)\s+(?:by|from)\s+")

(defn- encode
  "Encodes s for a URL path, with %20 rather than + for spaces."
  [s]
  (str/replace (URLEncoder/encode s "UTF-8") "+" "%20"))

(defn search-link
  "Returns a Spotify search link for a reminder title like \"Look into the album
  Animals by Pink Floyd\", which searches for \"Animals Pink Floyd\". The verb, a
  spoken \"song\", \"album\", \"artist\", \"track\" or \"band\", and \"by\" or
  \"from\" are left out of the search.

  The link has no /artists, /albums or /tracks filter: the Spotify app on iPhone
  reads that last part of the path as the search text."
  [title]
  (let [query (-> title
                  (str/replace-first lead-pattern "")
                  (str/replace-first kind-pattern "")
                  (str/replace by-pattern " ")
                  str/trim)]
    (str "https://open.spotify.com/search/" (encode query))))
