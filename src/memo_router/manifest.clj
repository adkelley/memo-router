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
