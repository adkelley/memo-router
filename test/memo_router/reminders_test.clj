(ns memo-router.reminders-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.reminders :as reminders])
  (:import (java.time LocalDate LocalDateTime)))

(def reminder {:title "Take out the trash" :body "Remind me to take out the trash."})

(defn- fake-osascript!
  "Writes an executable stand-in for osascript into dir and returns its path.
  It records its arguments (one per line) in args.txt next to itself, prints
  stderr, and exits with exit."
  [dir {:keys [stderr exit] :or {stderr "" exit 0}}]
  (let [bin (str (fs/path dir "fake-osascript"))]
    (spit bin (str "#!/bin/sh\n"
                   "printf '%s\\n' \"$@\" > \"$(dirname \"$0\")/args.txt\"\n"
                   ;; a heredoc, so apostrophes like \"Can't\" need no escaping
                   "cat >&2 <<'EOF'\n" stderr "\nEOF\n"
                   "exit " exit "\n"))
    (fs/set-posix-file-permissions bin "rwx------")
    bin))

(defn- recorded-args [dir]
  (str/split-lines (slurp (str (fs/path dir "args.txt")))))

(deftest reminder-command-test
  (testing "runs a JXA script with account, list, title, and body as separate arguments"
    (let [[bin lang-flag lang e-flag script & args]
          (reminders/reminder-command {:account "iCloud" :list "Inbox"} reminder)]
      (is (= "/usr/bin/osascript" bin))
      (is (= ["-l" "JavaScript" "-e"] [lang-flag lang e-flag]))
      (is (str/includes? script "Application('Reminders')"))
      (is (= ["iCloud" "Inbox" "Take out the trash" "Remind me to take out the trash."] args))))

  (testing "keeps memo text out of the script source"
    (let [script (nth (reminders/reminder-command {:list "Inbox"} reminder) 4)]
      (is (not (str/includes? script "Take out the trash")))))

  (testing "passes \"\" for a missing account, list or body, so the default list is used"
    (is (= ["" "" "Take out the trash" ""]
           (drop 5 (reminders/reminder-command {} {:title "Take out the trash"})))))

  (testing "leaves out the due arguments when there is no :due"
    (is (= 4 (count (drop 5 (reminders/reminder-command {:list "Inbox"} reminder))))))

  (testing "a timed reminder passes the date-time, and false for all-day"
    (is (= ["2026-11-03T15:00" "false"]
           (take-last 2 (reminders/reminder-command
                         {:list "Inbox"}
                         (assoc reminder :due (LocalDateTime/of 2026 11 3 15 0)))))))

  (testing "an all-day reminder passes midnight, since JavaScript reads a date alone as UTC"
    (is (= ["2026-11-03T00:00" "true"]
           (take-last 2 (reminders/reminder-command
                         {:list "Inbox"}
                         (assoc reminder :due (LocalDate/of 2026 11 3)))))))

  (testing ":bin overrides the osascript path"
    (is (= "/tmp/fake" (first (reminders/reminder-command {:bin "/tmp/fake"} reminder))))))

(deftest add-reminder!-test
  (testing "runs osascript with the built command and returns nil"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-osascript! dir {}) :list "Inbox"}]
        (is (nil? (reminders/add-reminder! config reminder)))
        (is (= ["Inbox" "Take out the trash" "Remind me to take out the trash."]
               (take-last 3 (recorded-args dir)))))))

  (testing "throws ex-info with :exit, :err and :title when osascript fails"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-osascript! dir {:stderr "Error: Can't get object. (-1728)" :exit 1})
                    :list "No such list"}
            e (is (thrown? clojure.lang.ExceptionInfo
                           (reminders/add-reminder! config reminder)))]
        (is (= {:exit 1 :err "Error: Can't get object. (-1728)\n" :title "Take out the trash"}
               (ex-data e)))))))
