(ns memo-router.test-runner
  "Finds every *_test.clj namespace under test/, runs it, and exits non-zero
  if any test fails or errors."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn file->ns
  "Turns test/memo_router/inbox_test.clj into memo-router.inbox-test."
  [file]
  (-> (str (fs/relativize "test" file))
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn test-namespaces []
  (->> (fs/glob "test" "**_test.clj")
       (map file->ns)
       sort))

(defn -main
  "Run with bb test. Accepts and ignores the options map that exec passes."
  [& _]
  (let [nses (test-namespaces)]
    (apply require nses)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
