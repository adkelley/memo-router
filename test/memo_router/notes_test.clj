(ns memo-router.notes-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.notes :as notes]))

(def note {:title "Idea: tags" :body "Group memos by project."})

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

(deftest note-html-test
  (testing "puts the title in an h1, so Notes uses it as the note's title"
    (is (= "<h1>Idea: tags</h1><p>Group memos by project.</p>"
           (notes/note-html note))))

  (testing "escapes &, < and > in the title and body"
    (is (= "<h1>Q&amp;A</h1><p>Use &lt;tags&gt; &amp; labels.</p>"
           (notes/note-html {:title "Q&A" :body "Use <tags> & labels."}))))

  (testing "leaves other punctuation alone"
    (is (= "<h1>Dana's \"idea\"</h1><p></p>"
           (notes/note-html {:title "Dana's \"idea\"" :body ""}))))

  (testing "a missing body becomes an empty paragraph"
    (is (= "<h1>Idea: tags</h1><p></p>"
           (notes/note-html {:title "Idea: tags"})))))

(deftest note-command-test
  (testing "runs a JXA script with account, folder, and HTML as separate arguments"
    (let [[bin lang-flag lang e-flag script & args]
          (notes/note-command {:account "iCloud" :folder "Notes"} note)]
      (is (= "/usr/bin/osascript" bin))
      (is (= ["-l" "JavaScript" "-e"] [lang-flag lang e-flag]))
      (is (str/includes? script "Application('Notes')"))
      (is (= ["iCloud" "Notes" "<h1>Idea: tags</h1><p>Group memos by project.</p>"] args))))

  (testing "looks the folder up within the account, not across all accounts"
    (let [script (nth (notes/note-command {} note) 4)]
      (is (str/includes? script "account.folders.byName"))
      (is (str/includes? script "account.defaultFolder"))
      (is (not (str/includes? script "app.folders")))))

  (testing "keeps memo text out of the script source"
    (let [script (nth (notes/note-command {} note) 4)]
      (is (not (str/includes? script "Group memos by project")))))

  (testing "passes \"\" for a missing account or folder, so the defaults are used"
    (is (= ["" ""] (take 2 (drop 5 (notes/note-command {} note))))))

  (testing ":bin overrides the osascript path"
    (is (= "/tmp/fake" (first (notes/note-command {:bin "/tmp/fake"} note))))))

(deftest add-note!-test
  (testing "runs osascript with the built command and returns nil"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-osascript! dir {}) :account "iCloud" :folder "Notes"}]
        (is (nil? (notes/add-note! config note)))
        (is (= ["iCloud" "Notes" "<h1>Idea: tags</h1><p>Group memos by project.</p>"]
               (take-last 3 (recorded-args dir)))))))

  (testing "throws ex-info with :exit, :err and :title when osascript fails"
    (fs/with-temp-dir [dir {}]
      (let [config {:bin (fake-osascript! dir {:stderr "Error: Can't get object. (-1728)" :exit 1})
                    :account "No such account"}
            e (is (thrown? clojure.lang.ExceptionInfo
                           (notes/add-note! config note)))]
        (is (= {:exit 1 :err "Error: Can't get object. (-1728)\n" :title "Idea: tags"}
               (ex-data e)))))))
