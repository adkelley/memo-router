(ns memo-router.config-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.config :as config]))

(defn- valid-config
  "A config that passes every check, built inside dir: a real memo folder, an
  executable stand-in for mlx_whisper, and a manifest path that does not
  exist yet (as on a first run)."
  [dir]
  (let [memo-dir (str (fs/create-dirs (fs/path dir "memos")))
        bin      (str (fs/path dir "mlx_whisper"))]
    (spit bin "#!/bin/sh\n")
    (fs/set-posix-file-permissions bin "rwx------")
    {:memo-dir  memo-dir
     :manifest  (str (fs/path dir "memo-router" "processed.txt"))
     :settle-ms 60000
     :whisper   {:bin bin :model "mlx-community/whisper-large-v3-turbo"}
     :shopping  {:shortcut "Add to Shopping List"}
     :reminders {:list "Inbox"}
     :notes     {:account "iCloud" :folder "Notes"}}))

(deftest problems-test
  (fs/with-temp-dir [dir {}]
    (let [good (valid-config dir)]

      (testing "a valid config has no problems"
        (is (= [] (config/problems good))))

      (testing "a manifest that does not exist yet is fine on the first run"
        (is (not (fs/exists? (:manifest good))))
        (is (= [] (config/problems good))))

      (testing "reports each required key that is missing or blank"
        (is (= [":memo-dir is missing or blank"]
               (config/problems (dissoc good :memo-dir))))
        (is (= [":manifest is missing or blank"]
               (config/problems (assoc good :manifest "  "))))
        (is (= [":whisper :model is missing or blank"]
               (config/problems (assoc-in good [:whisper :model] ""))))
        (is (= [":shopping :shortcut is missing or blank"]
               (config/problems (dissoc good :shopping))))
        (is (= [":shopping :shortcut is missing or blank"]
               (config/problems (assoc-in good [:shopping :shortcut] " ")))))

      (testing "a non-string value is reported rather than crashing"
        (is (= [":memo-dir is missing or blank"]
               (config/problems (assoc good :memo-dir 123)))))

      (testing ":memo-dir must be an existing directory"
        (is (= [":memo-dir is not a directory"]
               (config/problems (assoc good :memo-dir (str (fs/path dir "nope")))))))

      (testing ":manifest must not point at a directory"
        (is (= [":manifest is a directory, not a file"]
               (config/problems (assoc good :manifest (:memo-dir good))))))

      (testing ":settle-ms must be a non-negative integer"
        (doseq [bad [nil -1 "60000" 1.5]]
          (is (= [":settle-ms must be a non-negative integer"]
                 (config/problems (assoc good :settle-ms bad))))))

      (testing ":whisper :bin must exist"
        (is (some #{":whisper :bin does not exist"}
                  (config/problems (assoc-in good [:whisper :bin] (str (fs/path dir "missing")))))))

      (testing ":whisper :bin must be executable"
        (let [plain (str (fs/path dir "not-executable"))]
          (spit plain "")
          (fs/set-posix-file-permissions plain "rw-------")
          (is (= [":whisper :bin is not executable"]
                 (config/problems (assoc-in good [:whisper :bin] plain))))))

      (testing "optional sections may be left out entirely"
        (is (= [] (config/problems (dissoc good :reminders :notes))))
        (is (= [] (config/problems (assoc good :notes {} :reminders {})))))

      (testing "optional keys, when present, must not be blank"
        (is (= [":notes :folder is missing or blank"
                ":notes :account is missing or blank"
                ":reminders :account is missing or blank"
                ":reminders :list is missing or blank"]
               (config/problems (assoc good
                                       :notes {:account "" :folder nil}
                                       :reminders {:account "" :list " "}))))))

    (testing "reports every problem at once, in check order"
      (is (= [":memo-dir is missing or blank"
              ":manifest is missing or blank"
              ":settle-ms must be a non-negative integer"
              ":whisper :bin is missing or blank"
              ":whisper :model is missing or blank"
              ":shopping :shortcut is missing or blank"]
             (config/problems {}))))))

(deftest read-config-test
  (fs/with-temp-dir [dir {}]
    (let [path (str (fs/path dir "config.edn"))]

      (testing "throws ex-info with :path when the file is missing"
        (let [e (is (thrown? clojure.lang.ExceptionInfo (config/read-config path)))]
          (is (= {:path path} (ex-data e)))
          (is (str/includes? (ex-message e) "not found"))))

      (testing "throws ex-info with :path, keeping the parser error as the cause"
        (spit path "{:memo-dir \"/tmp\"")
        (let [e (is (thrown? clojure.lang.ExceptionInfo (config/read-config path)))]
          (is (= {:path path} (ex-data e)))
          (is (str/includes? (ex-message e) "not valid EDN"))
          (is (some? (ex-cause e)))))

      (testing "returns the map from a valid file"
        (spit path "{:settle-ms 60000}")
        (is (= {:settle-ms 60000} (config/read-config path)))))))

(deftest load-config-test
  (fs/with-temp-dir [dir {}]
    (let [path (str (fs/path dir "config.edn"))
          good (valid-config dir)]

      (testing "returns the config when it is valid"
        (spit path (pr-str good))
        (is (= good (config/load-config path))))

      (testing "throws ex-info listing the problems, with :path and :problems"
        (spit path (pr-str (dissoc good :memo-dir :manifest)))
        (let [e (is (thrown? clojure.lang.ExceptionInfo (config/load-config path)))]
          (is (= {:path path
                  :problems [":memo-dir is missing or blank"
                             ":manifest is missing or blank"]}
                 (ex-data e)))
          (is (str/includes? (ex-message e) ":memo-dir is missing or blank; :manifest")))))))
