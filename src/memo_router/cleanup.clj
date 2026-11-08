(ns memo-router.cleanup
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [memo-router.due :as due]))

(defn- recorded-before?
  "True if the memo at rel-path was recorded before the date cutoff. A path
  with no readable recording time is never old"
  [cutoff rel-path]
  (when-let [recorded (due/memo-time rel-path)]
    (.isBefore (.toLocalDate recorded) cutoff)))

(defn old-memos
  "Returns the memos in rel-paths that are in processed and were recorded more
  than days days before today, oldest first."
  [rel-paths processed today days]
  (let [cutoff (.minusDays today days)]
    (->> rel-paths
         (filter processed)
         (filter #(recorded-before? cutoff %))
         sort
         vec)))

(defn trash-name
  "The memo's name in the Trash: 2026-10-31/11-23-32.m4a becomes
  2026-10-31_11-23-32.m4a, so memos from different days can't collide"
  [rel-path]
  (str/replace rel-path #"/" "_"))

(defn trash!
  "Moves the memo at rel-path under memo-dir into trash-dir. Throws if a file
  with the same name is already there, rather than replacing it."
  [memo-dir rel-path trash-dir]
  (fs/move (fs/path memo-dir rel-path) (fs/path trash-dir (trash-name rel-path))))

(defn empty-dirs
  "The empty folders directly under memo-dir."
  [memo-dir]
  (->> (fs/list-dir memo-dir)
       (filter fs/directory?)
       (filter #(empty? (fs/list-dir %)))))

(defn remove-empty-dirs!
  "Deletes the empty folders directly under memo-dir, and returns how many."
  [memo-dir]
  (let [dirs (empty-dirs memo-dir)]
    (run! fs/delete dirs)
    (count dirs)))
