(ns memo-router.manifest
  (:require
   [clojure.string :as str]
   [babashka.fs :as fs]))

(defn read-processed
  "Read the manifest file into a set of relative paths.
  Returns #{} if the file doesn't exist yet."
  [path]
  (if (fs/regular-file? path)
    (->> path
         fs/read-all-lines
         (remove str/blank?)
         (map str/trim)
         set)
    #{}))

(defn mark-processed!
  "Appends rel-path, e.g. 2026-10-31/11-23-32.m4a, as one line to the manifest at path"
  [path rel-path]
  (let [rel-path (str/trim rel-path)]
    (fs/create-dirs (fs/parent path))
    (spit path (str rel-path "\n") :append true)))

(defn unmark-processed!
  "Removes rel-path from the manifest at path, so the next run handles that memo
  again.  Returns true if it was removed, or false if it wasn't listed or the manifest
  doesn't exist yet"
  [path rel-path]
  (let [rel-path (str/trim rel-path)]
    (if (contains? (read-processed path) rel-path)
      (do (->> (fs/read-all-lines path)
               (remove #(= rel-path (str/trim %)))
               (fs/write-lines path))
          true)
      false)))
