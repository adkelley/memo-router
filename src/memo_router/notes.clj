(ns memo-router.notes
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private script
  "function run(argv) {
     const [accountName, folderName, html] = argv;
     const app = Application('Notes');
     const account = accountName ? app.accounts.byName(accountName) : app.defaultAccount;
     const folder = folderName ? account.folders.byName(folderName) : account.defaultFolder;
     folder.notes.push(app.Note({body: html}));
   }")

(defn- html-escape [s]
  (str/escape s {\& "&amp;" \< "&lt;" \> "&gt;"}))

(defn note-html [{:keys [title body]}]
  (str "<h1>" (html-escape title) "</h1>"
       "<p>" (html-escape (or body "")) "</p>"))

(defn note-command [config note]
  (let [bin (:bin config "/usr/bin/osascript")]
    [bin "-l" "JavaScript" "-e" script
     (:account config "") (:folder config "") (note-html note)]))

(defn add-note!
  "Adds note (:title, :body) to the Notes folder named by (:folder config),
  or the default folder. Returns nil; throws ex-info (:exit :err :title) on failure."
  [config note]
  (let [{:keys [exit err]} (p/sh (note-command config note))]
    (when-not (zero? exit)
      (throw (ex-info "osascript failed to add note"
                      {:exit exit :err err :title (:title note)})))))
