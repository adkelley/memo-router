(ns memo-router.spotify
  (:require [memo-router.search-terms :as terms]))

(def ^:private wording
  {:verbs   ["look into" "look up" "listen to" "check out" "search for" "find"]
   :kinds   ["song" "album" "artist" "track" "band"]
   :joiners ["by" "from"]})

(defn search-link
  "Returns a Spotify search link for a reminder title like \"Look into the album
  Animals by Pink Floyd\", which searches for \"Animals Pink Floyd\". The verb, a
  spoken \"song\", \"album\", \"artist\", \"track\" or \"band\", and \"by\" or
  \"from\" are left out of the search.

  The link has no /artists, /albums or /tracks filter: the Spotify app on iPhone
  reads that last part of the path as the search text."
  [title]
  (str "https://open.spotify.com/search/" (terms/encode (terms/search-terms title wording))))
