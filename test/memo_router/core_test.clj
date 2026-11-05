(ns memo-router.core-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.classify :as classify]
   [memo-router.core :as core]
   [memo-router.manifest :as manifest])
  (:import (java.time LocalDate)))

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

(defn- fake-shortcuts!
  "A stand-in for shortcuts that appends the item on stdin as one line to
  calls.txt in its own folder, then exits with exit."
  [dir exit]
  (fs/create-dirs dir)
  (executable! (fs/path dir "fake-shortcuts")
               (str "#!/bin/sh\n"
                    "cat >> \"$(dirname \"$0\")/calls.txt\"\n"
                    "printf '\\n' >> \"$(dirname \"$0\")/calls.txt\"\n"
                    "exit " exit "\n")))

(defn- calls
  "What the fake command in dir was called with, one entry per call."
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
  "A complete config inside dir. :reminders-exit, :notes-exit and :shopping-exit
  set the exit code of the matching fake command."
  [dir & {:keys [reminders-exit notes-exit shopping-exit]
          :or   {reminders-exit 0 notes-exit 0 shopping-exit 0}}]
  {:memo-dir  (str (fs/create-dirs (fs/path dir "memos")))
   :manifest  (str (fs/path dir "state" "processed.txt"))
   :settle-ms 0
   :whisper   {:bin (fake-whisper! dir) :model "test-model"}
   :shopping  {:bin      (fake-shortcuts! (fs/path dir "shopping") shopping-exit)
               :shortcut "Add to Shopping List"}
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

  (testing "a time in the reminder is read from the memo's recording time"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-02/18-30-12.m4a" "Remind me to call Dana tomorrow at 3.")
        (is (= :reminder (core/process-memo! config false "2026-11-02/18-30-12.m4a")))
        (is (= ["Inbox | Call Dana | Remind me to call Dana tomorrow at 3. | 2026-11-03T15:00 | false"]
               (calls (fs/path dir "reminders")))))))

  (testing "a date alone creates an all-day reminder"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-02/18-30-12.m4a" "Remind me to call Dana tomorrow.")
        (core/process-memo! config false "2026-11-02/18-30-12.m4a")
        (is (= ["Inbox | Call Dana | Remind me to call Dana tomorrow. | 2026-11-03T00:00 | true"]
               (calls (fs/path dir "reminders")))))))

  (testing "a memo path with no time in it falls back to the current time"
    (fs/with-temp-dir [dir {}]
      (let [config   (test-config dir)
            tomorrow (.atStartOfDay (.plusDays (LocalDate/now) 1))]
        (add-memo! dir "imported/odd-name.m4a" "Remind me to call Dana tomorrow.")
        (core/process-memo! config false "imported/odd-name.m4a")
        (is (= [(str "Inbox | Call Dana | Remind me to call Dana tomorrow. | " tomorrow " | true")]
               (calls (fs/path dir "reminders")))))))

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

  (testing "a one-word transcript creates nothing but is marked processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/11-00-00.m4a" "You")
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

  (testing "a shopping request adds each item and marks the memo processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a"
                   "Remind me to add pickles, tomatoes, and flour to my shopping list.")
        (is (= :shopping (core/process-memo! config false "2026-11-01/09-00-00.m4a")))
        (is (= ["Pickles" "Tomatoes" "Flour"] (calls (fs/path dir "shopping"))))
        (is (= [] (calls (fs/path dir "reminders"))))
        (is (= #{"2026-11-01/09-00-00.m4a"} (processed config))))))

  (testing "with dry-run, a shopping request adds nothing"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Add milk to my grocery list.")
        (is (= :shopping (core/process-memo! config true "2026-11-01/09-00-00.m4a")))
        (is (= [] (calls (fs/path dir "shopping"))))
        (is (= #{} (processed config))))))

  (testing "when an item fails, it throws and the memo is not marked processed"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir :shopping-exit 1)]
        (add-memo! dir "2026-11-01/09-00-00.m4a" "Add milk to my grocery list.")
        (is (thrown? clojure.lang.ExceptionInfo
                     (core/process-memo! config false "2026-11-01/09-00-00.m4a")))
        (is (= #{} (processed config))))))

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
        (with-redefs [classify/classify (fn [_routes _now text] {:type :task :title "x" :body text})]
          (is (= {:failed 1} (run-with-config dir config))))
        (is (= #{} (processed config)))))))

(deftest re-process-test
  (testing "the next run handles the memo again, and creates a second reminder"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)
            memo   "2026-11-01/09-00-00.m4a"]
        (add-memo! dir memo "Remind me to call Dana.")
        (is (= {:reminder 1} (run-with-config dir config)))
        (is (str/includes?
             (with-out-str (core/re-process {:config-path (str (fs/path dir "config.edn"))
                                             :memo        memo}))
             "next run"))
        (is (= #{} (processed config)))
        (is (= {:reminder 1} (run-with-config dir config)))
        (is (= 2 (count (calls (fs/path dir "reminders"))))))))

  (testing "says so when the memo isn't in the manifest, and changes nothing"
    (fs/with-temp-dir [dir {}]
      (let [config (test-config dir)
            memo   "2026-11-01/09-00-00.m4a"]
        (add-memo! dir memo "Remind me to call Dana.")
        (run-with-config dir config)
        (is (str/includes?
             (with-out-str (core/re-process {:config-path (str (fs/path dir "config.edn"))
                                             :memo        "2026-11-01/10-00-00.m4a"}))
             "not in the manifest"))
        (is (= #{memo} (processed config)))))))
