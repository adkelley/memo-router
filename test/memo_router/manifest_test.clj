(ns memo-router.manifest-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [memo-router.manifest :as manifest]))

(deftest read-processed-test
  (fs/with-temp-dir [dir {}]
    (let [path (str (fs/path dir "processed.txt"))]

      (testing "returns #{} when the manifest does not exist yet"
        (is (= #{} (manifest/read-processed path))))

      (testing "returns #{} for a blank path or a directory"
        (is (= #{} (manifest/read-processed "")))
        (is (= #{} (manifest/read-processed (str dir)))))

      (testing "reads one relative path per line, trimming whitespace"
        (spit path "2026-10-31/08-00-00.m4a\n  2026-10-31/11-23-32.m4a  \n")
        (is (= #{"2026-10-31/08-00-00.m4a" "2026-10-31/11-23-32.m4a"}
               (manifest/read-processed path))))

      (testing "ignores blank lines"
        (spit path "2026-10-31/08-00-00.m4a\n\n   \n2026-10-31/11-23-32.m4a\n")
        (is (= #{"2026-10-31/08-00-00.m4a" "2026-10-31/11-23-32.m4a"}
               (manifest/read-processed path))))

      (testing "returns #{} for an empty manifest"
        (spit path "")
        (is (= #{} (manifest/read-processed path))))

      (testing "accepts a babashka.fs path as well as a string"
        (spit path "2026-10-31/08-00-00.m4a\n")
        (is (= #{"2026-10-31/08-00-00.m4a"}
               (manifest/read-processed (fs/path dir "processed.txt"))))))))

(deftest mark-processed!-test
  (fs/with-temp-dir [dir {}]
    (let [path (str (fs/path dir "memo-router" "processed.txt"))]

      (testing "creates the parent folder and the manifest on first use"
        (manifest/mark-processed! path "2026-10-31/08-00-00.m4a")
        (is (fs/regular-file? path)))

      (testing "appends one line per memo, so read-processed sees them all"
        (manifest/mark-processed! path "2026-10-31/11-23-32.m4a")
        (is (= "2026-10-31/08-00-00.m4a\n2026-10-31/11-23-32.m4a\n" (slurp path)))
        (is (= #{"2026-10-31/08-00-00.m4a" "2026-10-31/11-23-32.m4a"}
               (manifest/read-processed path))))

      (testing "trims whitespace around rel-path"
        (manifest/mark-processed! path "  2026-11-01/09-15-00.m4a \n")
        (is (contains? (manifest/read-processed path) "2026-11-01/09-15-00.m4a"))))))
