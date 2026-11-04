(ns memo-router.core
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [memo-router.classify :as classify]
   [memo-router.config :as config]
   [memo-router.due :as due]
   [memo-router.inbox :as inbox]
   [memo-router.notes :as notes]
   [memo-router.reminders :as reminders]
   [memo-router.whisper :as whisper]
   [memo-router.manifest :as manifest])
  (:import (java.time LocalDateTime)))

(def cli-spec
  {:config-path  {:desc "Path to config.edn" :default "config.edn"}
   :dry-run     {:desc "Transcribe and classify, but don't create anything or mark memos processed"
                 :coerce :boolean
                 :default false}})

(defn- route!
  "Creates the reminder or note for a classified memo."
  [config {:keys [type] :as memo}]
  (case type
    :reminder (reminders/add-reminder! (:reminders config) memo)
    :note (notes/add-note! (if-let [destination (:destination memo)]
                             (merge (select-keys (:notes config) [:bin]) destination)
                             (:notes config))
                           memo)))

(defn process-memo!
  "Transcribes, classifies, and routes one memo, then marks it processed.
   With dry-run, only transcribes and classifies. Returns :reminder, :note,
   or :skipped (blank transcript)."
  [{whisper-config :whisper :keys [memo-dir manifest] :as config} dry-run rel-path]
  (let [transcript (whisper/transcribe whisper-config (str (fs/path memo-dir rel-path)))]
    (if (str/blank? transcript)
      (do (when-not dry-run (manifest/mark-processed! manifest rel-path))
          :skipped)
      (let [now (or (due/memo-time rel-path) (LocalDateTime/now))
            {:keys [type] :as memo} (classify/classify (get-in config [:notes :routes]) now transcript)]
        (when-not dry-run
          (route! config memo)
          (manifest/mark-processed! manifest rel-path))
        type))))

(defn get-memos [{:keys [memo-dir manifest settle-ms]}]
  (let [now-ms (System/currentTimeMillis)
        processed (manifest/read-processed manifest)]
    (inbox/new-memos memo-dir processed now-ms settle-ms)))

(defn tally-memo
  "Reducing function: processes one memo and counts its outcome."
  [config dry-run tally rel-path]
  (let [outcome (try
                  (process-memo! config dry-run rel-path)
                  (catch Exception e
                    (println "failed:" rel-path (ex-message e) (ex-data e))
                    :failed))]
    (println rel-path "->" outcome)
    (update tally outcome (fnil inc 0))))

(defn run [{:keys [config-path dry-run]}]
  (let [config (config/load-config config-path)
        memos (get-memos config)]
    (when dry-run
      (println "dry-run mode: no changes will be made"))
    (let [tally (reduce (partial tally-memo config dry-run) {} memos)]
      (when (seq memos)
        (println "processed:" (count memos) "memos" tally))
      tally)))

(defn -main
  {:org.babashka/cli {:spec cli-spec}}
  [opts]
  (try
    (run opts)
    (catch Exception e
      (println "memo-router failed:" (ex-message e))
      (System/exit 1))))
