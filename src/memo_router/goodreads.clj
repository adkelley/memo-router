(ns memo-router.goodreads
  (:require [memo-router.search-terms :as terms]))

(def ^:private wording
  {:verbs   ["look into" "look up" "read" "check out" "search for" "find"]
   :kinds   ["book" "novel"]
   :joiners ["by"]})

(defn search-link
  "Returns a Goodreads search link for a reminder title like \"Read the book Dune
  by Frank Herbert\", which searches for \"Dune Frank Herbert\". The verb, a
  spoken \"book\" or \"novel\", and \"by\" are left out of the search."
  [title]
  (str "https://www.goodreads.com/search?q=" (terms/encode (terms/search-terms title wording))))
