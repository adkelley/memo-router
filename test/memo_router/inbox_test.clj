(ns memo-router.inbox-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [memo-router.inbox :as inbox]))

;; A fixed "current time" so tests never depend on the real clock.
(def now-ms 1800000000000)
(def settle-ms 60000)

(defn- touch!
  "Creates an empty file at rel-path under dir with the given modified time."
  [dir rel-path modified-ms]
  (let [path (fs/path dir rel-path)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) "")
    (fs/set-last-modified-time path modified-ms)))

(deftest memo-files-test
  (fs/with-temp-dir [dir {}]
    (touch! dir "2026-11-01/09-15-00.m4a" now-ms)
    (touch! dir "2026-10-31/11-23-32.m4a" now-ms)
    (touch! dir "2026-10-31/08-00-00.m4a" now-ms)
    (touch! dir "2026-10-31/notes.txt" now-ms)
    (touch! dir "2026-10-31/.10-00-00.m4a.icloud" now-ms)

    (testing "returns .m4a paths relative to dir, oldest first"
      (is (= ["2026-10-31/08-00-00.m4a"
              "2026-10-31/11-23-32.m4a"
              "2026-11-01/09-15-00.m4a"]
             (inbox/memo-files dir)))))

  (testing "returns an empty sequence for a missing folder"
    (is (empty? (inbox/memo-files "/no/such/memo-router/dir")))))

(deftest settled?-test
  (fs/with-temp-dir [dir {}]
    (touch! dir "old.m4a" (- now-ms 120000))
    (touch! dir "edge.m4a" (- now-ms settle-ms))
    (touch! dir "fresh.m4a" (- now-ms 1000))

    (testing "true once the file is older than settle-ms"
      (is (true? (inbox/settled? dir "old.m4a" now-ms settle-ms))))

    (testing "true at exactly settle-ms"
      (is (true? (inbox/settled? dir "edge.m4a" now-ms settle-ms))))

    (testing "false while the file may still be syncing"
      (is (false? (inbox/settled? dir "fresh.m4a" now-ms settle-ms))))))

(deftest new-memos-test
  (fs/with-temp-dir [dir {}]
    (let [old (- now-ms 120000)]
      (touch! dir "2026-10-31/08-00-00.m4a" old)
      (touch! dir "2026-10-31/11-23-32.m4a" old)
      (touch! dir "2026-10-31/13-50-07.m4a" (- now-ms 1000)))

    (testing "skips processed and unsettled memos"
      (is (= ["2026-10-31/11-23-32.m4a"]
             (inbox/new-memos dir #{"2026-10-31/08-00-00.m4a"} now-ms settle-ms))))

    (testing "returns every settled memo when nothing is processed"
      (is (= ["2026-10-31/08-00-00.m4a" "2026-10-31/11-23-32.m4a"]
             (inbox/new-memos dir #{} now-ms settle-ms))))

    (testing "returns a vector"
      (is (vector? (inbox/new-memos dir #{} now-ms settle-ms))))))
