(ns memo-router.config
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(defn read-config
  "Reads the EDN config at path, returning a map. Throws ex-info with :path
  if the file is missing or isn't valid EDN."
  [path]
  (when-not (fs/regular-file? path)
    (throw (ex-info (str "Config file not found: " path) {:path path})))
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (throw (ex-info (str "Config file is not valid EDN: " path) {:path path} e)))))

(defn- present? [s] (and (string? s) (not (str/blank? s))))

(defn problems
  "Returns a vector of problems found in the config map."
  [{:keys [memo-dir manifest settle-ms whisper reminders notes shopping]}]
  (cond-> []
    (not (present? memo-dir)) (conj ":memo-dir is missing or blank")
    (and (present? memo-dir)
         (not (fs/directory? memo-dir))) (conj ":memo-dir is not a directory")
    (not (present? manifest)) (conj ":manifest is missing or blank")
    (and (present? manifest)
         (fs/directory? manifest)) (conj ":manifest is a directory, not a file")
    (not (nat-int? settle-ms)) (conj ":settle-ms must be a non-negative integer")
    (not (present? (:bin whisper))) (conj ":whisper :bin is missing or blank")
    (and (present? (:bin whisper))
         (not (fs/regular-file? (:bin whisper)))) (conj ":whisper :bin does not exist")
    (and (present? (:bin whisper))
         (not (fs/executable? (:bin whisper)))) (conj ":whisper :bin is not executable")
    (not (present? (:model whisper))) (conj ":whisper :model is missing or blank")
    (not (present? (:shortcut shopping))) (conj ":shopping :shortcut is missing or blank")
    (and (contains? notes :folder)
         (not (present? (:folder notes)))) (conj ":notes :folder is missing or blank")
    (and (contains? notes :account)
         (not (present? (:account notes)))) (conj ":notes :account is missing or blank")
    (and (contains? reminders :list)
         (not (present? (:list reminders)))) (conj ":reminders :list is missing or blank")))

(defn load-config
  "Reads and validates the EDN config at path, returning the config map.
  Throws ex-info with :path if the file is missing or invalid EDN, or with
  :path and :problems if validation fails."
  [path]
  (let [config (read-config path)
        found  (problems config)]
    (when (seq found)
      (throw (ex-info (str "Invalid config: " (str/join "; " found))
                      {:path path :problems found})))
    config))
