(ns memo-router.reminders
  (:require [babashka.process :as p]))

(def ^:private script
  "function run(argv) {
     const [listName, title, body] = argv;
     const app = Application('Reminders');
     const list = listName ? app.lists.byName(listName) : app.defaultList;
     list.reminders.push(app.Reminder({name: title, body: body}));
   }")

(defn reminder-command [config {:keys [title body]}]
  (let [bin (:bin config "/usr/bin/osascript")
        list (:list config "")]
    (vector bin "-l" "JavaScript" "-e" script list title (or body ""))))

(defn add-reminder!
  "Adds reminder (:title, :body) to the Reminders list named by (:list config),
  or the default list. Returns nil; throws ex-info (:exit :err :title) on failure."
  [config reminder]
  (let [{:keys [exit err]} (p/sh (reminder-command config reminder))]
    (when-not (zero? exit)
      (throw (ex-info "osascript failed to add reminder"
                      {:exit exit :err err :title (:title reminder)})))))
