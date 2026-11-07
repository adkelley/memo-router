(ns memo-router.links
  (:require [memo-router.spotify :as spotify]))

(defmulti link
  "Returns a link for a reminder title from the named service, or nil."
  (fn [service _title] service))

(defmethod link :default [_ _] nil)

(defmethod link :spotify [_ title]
  (spotify/search-link title))

(defn add-link
  "Adds :link to memo when its :destination names a service that has a link for its
  title. Otherwise, returns memo unchanged."
  [memo]
  (if-let [url (some-> (get-in memo [:destination :link])
                       (link (:title memo)))]
    (assoc memo :link url)
    memo))
