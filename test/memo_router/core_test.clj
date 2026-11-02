(ns memo-router.core-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.classify :as classify]
   [memo-router.core :as core]
   [memo-router.manifest :as manifest]))

;; End-to-end tests of the pipeline. mlx_whisper and osascript are replaced by
;; small shell scripts, so nothing is transcribed and no real reminders or
;; notes are created.

(defn- executable! [path content]
  (spit (str path) content)
  (fs/set-posix-file-permissions path "rwx------")
  (str path))

(defn- fake-whisper!
  "A stand-in for mlx_whisper that copies <dir>/transcripts/<memo name>.txt
  into the output folder, so each memo can have its own transcript."
  [dir]
  (executable! (fs/path dir "fake-mlx-whisper")
               (str "#!/bin/sh\n"
                    "audio=\"$1\"\n"
                    "while [ $# -gt 0 ]; do\n"
                    "  [ \"$1\" = --output-dir ] && out=\"$2\"\n"
                    "  shift\n"
                    "done\n"
                    "base=$(basename \"$audio\" .m4a)\n"
                    "cp \"" (fs/path dir "transcripts") "/$base.txt\" \"$out/$base.txt\"\n")))

(defn- fake-osascript!
  "A stand-in for osascript that appends the arguments after the script
  (e.g. Inbox | Call Dana | <body>) as one line to calls.txt in its own
  folder, then exits with exit."
  [dir exit]
  (fs/create-dirs dir)
  (executable! (fs/path dir "fake-osascript")
               (str "#!/bin/sh\n"
                    "shift 4\n"  ; skip -l JavaScript -e <script>
                    "line=\"$1\"; shift\n"
                    "for arg; do line=\"$line | $arg\"; done\n"
                    "printf '%s\\n' \"$line\" >> \"$(dirname \"$0\")/calls.txt\"\n"
                    "exit " exit "\n")))

(defn- calls
  "What the fake osascript in dir was called with, one entry per call."
  [dir]
  (let [f (str (fs/path dir "calls.txt"))]
    (if (fs/exists? f) (str/split-lines (slurp f)) [])))

(defn- add-memo!
  "Creates an empty memo file and the transcript the fake whisper returns for it."
  [dir rel-path transcript]
  (let [memo (fs/path dir "memos" rel-path)]
    (fs/create-dirs (fs/parent memo))
    (spit (str memo) "")
    (fs/create-dirs (fs/path dir "transcripts"))
    (spit (str (fs/path dir "transcripts" (str (fs/strip-ext (fs/file-name rel-path)) ".txt")))
          transcript)))

(defn- test-config
  "A complete config inside dir. :reminders-exit / :notes-exit set the exit
  code of the matching fake osascript."
  [dir & {:keys [reminders-exit notes-exit] :or {reminders-exit 0 notes-exit 0}}]
  {:memo-dir  (str (fs/create-dirs (fs/path dir "memos")))
   :manifest  (str (fs/path dir "state" "processed.txt"))
   :settle-ms 0
   :whisper   {:bin (fake-whisper! dir) :model "test-model"}
   :reminders {:bin (fake-osascript! (fs/path dir "reminders") reminders-exit) :list "Inbox"}
   :notes     {:bin (fake-osascript! (fs/path dir "notes") notes-exit)
               :account "iCloud" :folder "Notes"}})

(defn- processed [config]
  (manifest/read-processed (:manifest config)))

(deftest process-memo!-test
  (testing "a reminder is created in Reminders and the memo is marked processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (is (= :reminder (core/process-memo! config false "2026-11-01/09-00-00.m4a")))
        (is (= ["Inbox | Call Dana | Remind me to call Dana."] (calls (fs/path dir "reminders"))))
        (is (= [] (calls (fs/path dir "notes"))))
        (is (= #{"2026-11-01/09-00-00.m4a"} (processed config))))))

  (testing "anything else becomes a note"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/10-00-00.m4a" "Idea: group memos by project.")
        (is (= :note (core/process-memo! config false "2026-11-01/10-00-00.m4a")))
        (is (= ["iCloud | Notes | <h1>Idea: group memos by project</h1><p>Idea: group memos by project.</p>"]
               (calls (fs/path dir "notes"))))
        (is (= [] (calls (fs/path dir "reminders"))))
        (is (= #{"2026-11-01/10-00-00.m4a"} (processed config))))))

  (testing "a blank transcript creates nothing but is marked processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/11-00-00.m4a" "   ")
        (is (= :skipped (core/process-memo! config false "2026-11-01/11-00-00.m4a")))
        (is (= [] (calls (fs/path dir "reminders")) (calls (fs/path dir "notes"))))
        (is (= #{"2026-11-01/11-00-00.m4a"} (processed config))))))

  (testing "dry-run classifies but creates nothing and leaves the manifest alone"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (is (= :reminder (core/process-memo! config true "2026-11-01/09-00-00.m4a")))
        (is (= [] (calls (fs/path dir "reminders"))))
        (is (not (fs/exists? (:manifest config)))))))

  (testing "when Reminders fails, it throws and the memo is not marked processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir :reminders-exit 1)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (is (thrown? clojure.lang.ExceptionInfo
                     (core/process-memo! config false "2026-11-01/09-00-00.m4a")))
        (is (= #{} (processed config)))))))

(defn- run-with-config [dir config & {:keys [dry-run] :or {dry-run false}}]
  (let [path (str (fs/path dir "config.edn"))]
    (spit path (pr-str config))
    ;; run logs with println; keep test output quiet
    (let [result (atom nil)]
      (with-out-str (reset! result (core/run {:config-path path :dry-run dry-run})))
      @result)))

(deftest run-test
  (testing "routes every new memo, returns a tally, and skips them next time"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (add-memo! dir "2026-11-01/10-00-00.m4a" "Idea: group memos by project.")
        (is (= {:reminder 1 :note 1} (run-with-config dir config)))
        (is (= #{"2026-11-01/09-00-00.m4a" "2026-11-01/10-00-00.m4a"} (processed config)))
        (is (= {} (run-with-config dir config))))))

  (testing "dry-run returns the same tally without changing anything"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (is (= {:reminder 1} (run-with-config dir config :dry-run true)))
        (is (= [] (calls (fs/path dir "reminders"))))
        (is (= #{} (processed config))))))

  (testing "one failing memo is counted and retried later; the others still run"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir :notes-exit 1)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (add-memo! dir "2026-11-01/10-00-00.m4a" "Idea: group memos by project.")
        (is (= {:reminder 1 :failed 1} (run-with-config dir config)))
        (is (= #{"2026-11-01/09-00-00.m4a"} (processed config))))))

  (testing "an unexpected :type fails loudly instead of being marked processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Remind me to call Dana.")
        (with-redefs [classify/classify (fn [text] {:type :task :title "x" :body text})]
          (is (= {:failed 1} (run-with-config dir config))))
        (is (= #{} (processed config)))))))
