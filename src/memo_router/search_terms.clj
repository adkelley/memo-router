(ns memo-router.search-terms
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.util.regex Pattern)))

(defn- any-of
  "A regex fragment matching any of words, each taken literally."
  [words]
  (str "(?:" (str/join "|" (map #(Pattern/quote %) words)) ")"))

(defn search-terms
  "Returns what to search for in a reminder title, for a site's own wording:
  leaves out a leading verb from verbs (\"look into\"), then a kind word from
  kinds, with or without \"the\" (\"the album\"), and turns any word in
  joiners (\"by\") into a space. All matching ignores case."
  [title {:keys [verbs kinds joiners]}]
  (str/trim
   (cond-> title
     (seq verbs)   (str/replace-first (re-pattern (str "(?i)^" (any-of verbs) "\\s+")) "")
     (seq kinds)   (str/replace-first (re-pattern (str "(?i)^(?:the\\s+)?" (any-of kinds) "\\s+")) "")
     (seq joiners) (str/replace (re-pattern (str "(?i)\\s+" (any-of joiners) "\\s+")) " "))))

(defn encode
  "Encodes s for a URL, with %20 rather than + for spaces, which works in both
  a path and a query string."
  [s]
  (str/replace (URLEncoder/encode s "UTF-8") "+" "%20"))
