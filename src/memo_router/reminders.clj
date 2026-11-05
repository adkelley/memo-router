(ns memo-router.reminders
  (:require [babashka.process :as p])
  (:import (java.time LocalDate)))

(def ^:private script
  "function run(argv) {
     const [accountName, listName, title, body, due, allDay] = argv;
     const app = Application('Reminders');
     const lists = accountName ? app.accounts.byName(accountName).lists : app.lists;
     const list = listName ? lists.byName(listName) : app.defaultList;
     const props = {name: title, body: body}
     if (due) props[allDay === 'true' ? 'alldayDueDate' : 'dueDate'] = new Date(due);
     list.reminders.push(app.Reminder(props));
   }")

(defn- due-args
  "The due date as osascript arguments: a local date-time string, and whether
   the reminder is all-day. A LocalDate means all-day."
  [due]
  (if (instance? LocalDate due)
    [(str (.atStartOfDay due)) "true"]
    [(str due) "false"]))

(defn reminder-command [config {:keys [title body due]}]
  (let [bin (:bin config "/usr/bin/osascript")
        account (:account config "")
        list (:list config "")]
    (cond-> [bin "-l" "JavaScript" "-e" script account list title (or body "")]
      due (into (due-args due)))))

(defn add-reminder!
  "Adds reminder (:title, :body, optional :due) to the Reminders list named by (:list config),
    in (:account config) if given, or the default list. Returns nil; throws ex-info
    (:exit :err :title) on failure."
  [config reminder]
  (let [{:keys [exit err]} (p/sh (reminder-command config reminder))]
    (when-not (zero? exit)
      (throw (ex-info "osascript failed to add reminder"
                      {:exit exit :err err :title (:title reminder)})))))
