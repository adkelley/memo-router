(ns memo-router.cleanup-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [are deftest is testing]]
   [memo-router.cleanup :as cleanup])
  (:import (java.time LocalDate)))

;; Saturday 7 November 2026, so 7 days back is Saturday 31 October.
(def today (LocalDate/of 2026 11 7))

(def memos ["2026-10-30/09-00-00.m4a"
            "2026-10-31/09-00-00.m4a"
            "2026-11-06/09-00-00.m4a"])

(deftest old-memos-test
  (testing "processed memos recorded before the cutoff, oldest first"
    (is (= ["2026-10-30/09-00-00.m4a"]
           (cleanup/old-memos memos (set memos) today 7))))

  (testing "a memo recorded exactly days days ago is kept"
    (is (not-any? #{"2026-10-31/09-00-00.m4a"}
                  (cleanup/old-memos memos (set memos) today 7))))

  (testing "an unprocessed memo is kept, however old"
    (is (= [] (cleanup/old-memos memos #{"2026-11-06/09-00-00.m4a"} today 7))))

  (testing "a path with no readable date is kept"
    (is (= [] (cleanup/old-memos ["notes/odd-name.m4a"] #{"notes/odd-name.m4a"} today 7))))

  (testing "days changes the cutoff"
    (are [days expected] (= expected (cleanup/old-memos memos (set memos) today days))
      30 []
      7  ["2026-10-30/09-00-00.m4a"]
      1  ["2026-10-30/09-00-00.m4a" "2026-10-31/09-00-00.m4a"]
      0  ["2026-10-30/09-00-00.m4a" "2026-10-31/09-00-00.m4a" "2026-11-06/09-00-00.m4a"]))

  (testing "sorts the result, whatever order the paths came in"
    (is (= ["2026-10-30/09-00-00.m4a" "2026-10-31/09-00-00.m4a"]
           (cleanup/old-memos (reverse memos) (set memos) today 1)))))

(deftest trash-name-test
  (is (= "2026-10-31_11-23-32.m4a" (cleanup/trash-name "2026-10-31/11-23-32.m4a"))))

(defn- add-file! [dir rel-path]
  (let [path (fs/path dir rel-path)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) "audio")))

(deftest trash!-test
  (testing "moves the memo into the trash under its trash name"
    (fs/with-temp-dir [dir {}]
      (let [memo-dir (fs/path dir "memos")
            trash    (str (fs/create-dirs (fs/path dir "trash")))]
        (add-file! memo-dir "2026-10-31/11-23-32.m4a")
        (cleanup/trash! memo-dir "2026-10-31/11-23-32.m4a" trash)
        (is (not (fs/exists? (fs/path memo-dir "2026-10-31/11-23-32.m4a"))))
        (is (= "audio" (slurp (str (fs/path trash "2026-10-31_11-23-32.m4a"))))))))

  (testing "throws rather than replacing a file already in the trash"
    (fs/with-temp-dir [dir {}]
      (let [memo-dir (fs/path dir "memos")
            trash    (str (fs/create-dirs (fs/path dir "trash")))]
        (add-file! memo-dir "2026-10-31/11-23-32.m4a")
        (spit (str (fs/path trash "2026-10-31_11-23-32.m4a")) "older")
        (is (thrown? Exception (cleanup/trash! memo-dir "2026-10-31/11-23-32.m4a" trash)))
        (is (= "older" (slurp (str (fs/path trash "2026-10-31_11-23-32.m4a")))))
        (is (fs/exists? (fs/path memo-dir "2026-10-31/11-23-32.m4a")))))))

(deftest empty-dirs-test
  (fs/with-temp-dir [dir {}]
    (fs/create-dirs (fs/path dir "2026-10-30"))
    (fs/create-dirs (fs/path dir "2026-10-31"))
    (add-file! dir "2026-11-06/09-00-00.m4a")
    (add-file! dir "2026-11-07/.DS_Store")

    (testing "lists only the folders with nothing in them"
      (is (= #{"2026-10-30" "2026-10-31"}
             (set (map (comp str fs/file-name) (cleanup/empty-dirs dir))))))

    (testing "remove-empty-dirs! deletes them and returns how many"
      (is (= 2 (cleanup/remove-empty-dirs! dir)))
      (is (= #{"2026-11-06" "2026-11-07"}
             (set (map (comp str fs/file-name) (fs/list-dir dir))))))

    (testing "returns 0 when there is nothing to remove"
      (is (= 0 (cleanup/remove-empty-dirs! dir))))))
