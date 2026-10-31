(ns memo-router.inbox
  (:require
   [babashka.fs :as fs]))

(defn memo-files
  "Returns the .m4a files under dir as sorted paths relative to dir,
    e.g. (\"2026-10-31/11-23-32.m4a\")."
  [dir]
  (->> (fs/glob dir "**.m4a")
       (map #(fs/relativize dir %))
       (map str)
       (sort)))

(defn settled?
  "Returns true if the memo at rel-path (relative to dir) was last modified
  at least settle-ms before now-ms, meaning iCloud has likely finished
  syncing it."
  [dir rel-path now-ms settle-ms]
  (let [modified-ms (-> (fs/path dir rel-path)
                        fs/last-modified-time
                        fs/file-time->millis)]
    (>= (- now-ms modified-ms) settle-ms)))

(defn new-memos
  "Returns a vector of memo paths under dir, relative to dir and oldest
  first, that are not in processed and have settled (see settled?) as of
  now-ms.

  processed must be a set of relative paths, e.g.
  #{\"2026-10-31/11-23-32.m4a\"}; use #{} when nothing has been processed.
  It is used as the predicate for remove, so a string or vector will fail."
  [dir processed now-ms settle-ms]
  (->> (memo-files dir)
       (remove processed)
       (filterv #(settled? dir % now-ms settle-ms))))
