(ns memo-router.whisper
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]))

(defn transcribe
  "Runs mlx_whisper on audio-path and returns the trimmed transcript.
  whisper-config needs :bin and :model. Throws ex-info (:exit :err :file)
  on failure."
  [whisper-config audio-path]
  (fs/with-temp-dir [out-dir {:prefix "memo-router-"}]
    (let [out-dir (str out-dir)
          {:keys [bin model]} whisper-config
          {:keys [exit err]}
          (process/sh [bin audio-path
                       "--model" model
                       "--output-dir" out-dir
                       "--output-format" "txt"
                       "--verbose" "False"])]
      (when-not (zero? exit)
        (throw (ex-info "mlx-whisper failed" {:exit exit :err err :file audio-path}))))
    (let [base (fs/strip-ext (fs/file-name audio-path))]
      (-> (fs/path out-dir (str base ".txt"))
          str
          slurp
          str/trim))))
