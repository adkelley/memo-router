(ns memo-router.inbox
  (:require
   [babashka.fs :as fs]))

(defn- memo-files
  "Returns the .m4a files under dir as sorted paths relative to dir,
  e.g. (\"2026-10-31/11-23-32.m4a\")."
  [dir]
  (->> (fs/glob dir "**.m4a")
       (map #(fs/relativize dir %))
       (map str)
       (sort)))

(defn- settled?
  "Returns true if the memo at rel-path (relative to dir) was last modified
  at least settle-ms before now-ms, meaning iCloud has likely finished
  syncing it."
  [dir rel-path now-ms settle-ms]
  (let [modified-ms (-> (fs/path dir rel-path)
                        fs/last-modified-time
                        fs/file-time->millis)]
    (>= (- now-ms modified-ms) settle-ms)))

(defn new-memos
  "Returns the memos under dir that still need processing, as a vector of
  paths relative to dir, oldest first, e.g. [\"2026-10-31/11-23-32.m4a\"].

  dir is the Just Press Record iCloud folder, :memo-dir in config.edn, e.g.
  \"/Users/<you>/Library/Mobile Documents/iCloud~com~openplanetsoftware~just-press-record/Documents\".
  Memos sit one level down in date folders (<dir>/2026-10-31/11-23-32.m4a);
  .m4a files at any depth are found. Pass an absolute path (string or
  babashka.fs path). A missing folder returns [] rather than throwing, so
  check it exists first (config/problems does).

  A memo is left out if:
  - it is already in processed, the set of relative paths read from the
    manifest, e.g. #{\"2026-10-31/08-00-00.m4a\"}; use #{} when nothing has
    been processed. It must be a set, since it is used as the predicate
    for remove, so a string or vector will fail.
  - it changed within the last settle-ms milliseconds, so iCloud may still
    be syncing it; it is picked up on a later run instead.

  now-ms is the current time in epoch milliseconds, normally
  (System/currentTimeMillis). Passing it in rather than reading the clock
  here keeps the result repeatable in tests. settle-ms comes from
  :settle-ms in config.edn, e.g. 60000 for one minute."
  [dir processed now-ms settle-ms]
  (->> (memo-files dir)
       (remove processed)
       (filterv #(settled? dir % now-ms settle-ms))))
