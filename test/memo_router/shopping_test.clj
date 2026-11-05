(ns memo-router.shopping-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.shopping :as shopping]))

(defn- fake-shortcuts!
  "Writes an executable stand-in for shortcuts into dir and returns its path.
  It appends its arguments and standard input as one line to calls.txt next to
  itself. If the item equals fail-on, it prints stderr and exits with 1."
  [dir {:keys [stderr fail-on] :or {stderr "" fail-on ""}}]
  (let [bin (str (fs/path dir "fake-shortcuts"))]
    (spit bin (str "#!/bin/sh\n"
                   "item=$(cat)\n"
                   "printf '%s | %s | %s\\n' \"$1\" \"$2\" \"$item\" >> \"$(dirname \"$0\")/calls.txt\"\n"
                   "if [ \"$item\" = \"" fail-on "\" ]; then\n"
                   "  printf '%s' '" stderr "' >&2\n"
                   "  exit 1\n"
                   "fi\n"))
    (fs/set-posix-file-permissions bin "rwx------")
    bin))

(defn- calls [dir]
  (let [f (str (fs/path dir "calls.txt"))]
    (if (fs/exists? f) (str/split-lines (slurp f)) [])))

(deftest add-item!-test
  (testing "runs the shortcut by name, sends the item on stdin, and returns nil"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-shortcuts! dir {}) :shortcut "Add to Shopping List"}]
        (is (nil? (shopping/add-item! config "Olive oil")))
        (is (= ["run | Add to Shopping List | Olive oil"] (calls dir))))))

  (testing "throws ex-info with :exit, :err and :item when shortcuts fails"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin      (fake-shortcuts! dir {:stderr "no such shortcut" :fail-on "Flour"})
                    :shortcut "Missing"}
            e      (is (thrown? clojure.lang.ExceptionInfo
                                (shopping/add-item! config "Flour")))]
        (is (= {:exit 1 :err "no such shortcut" :item "Flour"} (ex-data e)))))))

(deftest add-items!-test
  (testing "adds each item in turn"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-shortcuts! dir {}) :shortcut "Add to Shopping List"}]
        (shopping/add-items! config {:items ["Pickles" "Flour"]})
        (is (= ["run | Add to Shopping List | Pickles"
                "run | Add to Shopping List | Flour"]
               (calls dir))))))

  (testing "stops at the first failure, leaving later items unsent"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-shortcuts! dir {:fail-on "Flour"}) :shortcut "Add to Shopping List"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (shopping/add-items! config {:items ["Pickles" "Flour" "Eggs"]})))
        (is (= ["run | Add to Shopping List | Pickles"
                "run | Add to Shopping List | Flour"]
               (calls dir)))))))
