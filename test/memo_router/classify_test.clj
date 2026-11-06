(ns memo-router.classify-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [memo-router.classify :as classify])
  (:import (java.time LocalDate LocalDateTime)))

(deftest reminder?-test
  (testing "true when the transcript starts with a trigger phrase, any case"
    (are [text] (true? (classify/reminder? text))
      "Remind me to look into Ring software for Clojure."
      "remind me call Dana tomorrow"
      "  REMIND ME to stretch"
      "Reminder: renew passport."
      "Don't forget to water the plants!"))

  (testing "false when the phrase is missing or not at the start"
    (are [text] (false? (classify/reminder? text))
      "I told Dana to remind me about lunch."
      "Idea: group memos by project. Maybe use tags."
      "Reminders app is handy.")))

(deftest reminder-title-test
  (testing "drops the trigger phrase and end punctuation, capitalises the first letter"
    (are [text title] (= title (classify/reminder-title text))
      "Remind me to look into Ring software for Clojure." "Look into Ring software for Clojure"
      "remind me call Dana tomorrow"                       "Call Dana tomorrow"
      "Reminder: renew passport."                          "Renew passport"
      "Don't forget to water the plants!"                  "Water the plants"))

  (testing "only strips \"to\" as a whole word, not the start of \"tomorrow\""
    (is (= "Tomorrow to stretch"
           (classify/reminder-title "remind me tomorrow to stretch"))))

  (testing "keeps the capitalisation of later words"
    (is (= "Email Dana about Datomic"
           (classify/reminder-title "remind me to email Dana about Datomic.")))))

(deftest note-title-test
  (testing "uses the first sentence without its end punctuation"
    (are [text title] (= title (classify/note-title text))
      "Idea: group memos by project. Maybe use tags."  "Idea: group memos by project"
      "I told Dana to remind me about lunch."          "I told Dana to remind me about lunch"
      "What if memos had tags? Could be useful."       "What if memos had tags"
      "no punctuation at all"                          "No punctuation at all"))

  (testing "keeps a full stop inside the sentence"
    (is (= "Upgrade to Babashka v1.13"
           (classify/note-title "Upgrade to Babashka v1.13. It has new fs functions."))))

  (testing "caps the title at 8 words, adding ... only when words were cut"
    (are [text title] (= title (classify/note-title text))
      ;; exactly 8 words: the boundary, left whole
      "Idea for the wiki group memos by project."
      "Idea for the wiki group memos by project"

      ;; one long spoken sentence, as Whisper often produces
      "idea for the wiki we could group memos by project and maybe use tags"
      "Idea for the wiki we could group memos..."

      ;; a long first sentence followed by more sentences
      "We could group memos by project and tag each one. Then search by tag."
      "We could group memos by project and tag..."

      ;; extra whitespace does not count as words
      "  Idea   for the   wiki  "
      "Idea for the wiki")))

(def note-routes {"Acme"      {:account "Acme"}
                  "Acme Corp" {:account "Acme Corp" :folder "Memos"}})

(def reminder-routes {"Home"         {:list "Home"}
                      "ADK Partners" {:account "ADK" :list "Tasks"}})

(deftest note-route-test
  (testing "matches a keyword at the start, ignoring case, with optional \"note\""
    (are [text remaining] (= {:destination {:account "Acme"} :text remaining}
                             (classify/note-route note-routes text))
      "Acme note: call the auditor."  "call the auditor."
      "acme, call the auditor."       "call the auditor."
      "Acme. Call the auditor."       "Call the auditor."
      "ACME call the auditor."        "call the auditor."))

  (testing "the longest keyword wins"
    (is (= {:account "Acme Corp" :folder "Memos"}
           (:destination (classify/note-route note-routes "Acme Corp: call the auditor.")))))

  (testing "nil when no keyword starts the text, or there are no routes"
    (are [routes text] (nil? (classify/note-route routes text))
      note-routes "Call Acme about the audit."
      note-routes "Acmeville is lovely."
      nil         "Acme note: call the auditor.")))

(deftest reminder-route-test
  (testing "matches a name and \"list\" at the start, ignoring case and punctuation"
    (are [text remaining] (= {:destination {:list "Home"} :text remaining}
                             (classify/reminder-route reminder-routes text))
      "Home list, remind me to do the dishes." "remind me to do the dishes."
      "Home list. Remind me to do the dishes." "Remind me to do the dishes."
      "home list: do the dishes."              "do the dishes."
      "HOME LIST remind me to do the dishes"   "remind me to do the dishes"))

  (testing "a name can be several words, and carry its own account"
    (is (= {:account "ADK" :list "Tasks"}
           (:destination (classify/reminder-route reminder-routes "ADK Partners list, call Dana.")))))

  (testing "nil without \"list\", for an unknown name, or with no routes"
    (are [routes text] (nil? (classify/reminder-route routes text))
      reminder-routes "Home, remind me to do the dishes."
      reminder-routes "Home lists are useful."
      reminder-routes "Garage list, remind me to sweep."
      reminder-routes "Remind me to do the dishes."
      nil             "Home list, remind me to do the dishes.")))

(deftest extract-priority-test
  (testing "reads each level, ignoring case"
    (are [text priority] (= priority (:priority (classify/extract-priority text)))
      "Remind me to call Dana, high priority."   1
      "Remind me to call Dana, HIGH PRIORITY."   1
      "Remind me to call Dana, medium priority." 5
      "Remind me to call Dana, low priority."    9
      "Remind me to call Dana, high-priority."   1
      "Urgent, remind me to call Dana."          1
      "Remind me urgently to call Dana."         1))

  (testing "removes the phrase wherever it is, with its punctuation"
    (are [text remaining] (= remaining (:text (classify/extract-priority text)))
      "Remind me to call Dana, high priority."      "Remind me to call Dana"
      "Remind me to call Dana. Low priority."       "Remind me to call Dana"
      "High priority, remind me to call Dana."      "remind me to call Dana."
      "Remind me, high priority, to call Dana."     "Remind me to call Dana."
      "Remind me to high priority call Dana today." "Remind me to call Dana today."))

  (testing "nil when there is no priority phrase"
    (are [text] (nil? (classify/extract-priority text))
      "Remind me to call Dana."
      "Remind me to review priority items."
      "Remind me to check the high school schedule.")))

(deftest noise?-test
  (testing "true for a blank or one-word transcript"
    (are [text] (true? (classify/noise? text))
      ""
      "   "
      "You"
      "  You.  "))

  (testing "false for two or more words"
    (are [text] (false? (classify/noise? text))
      "Call Dana"
      "  Call   Dana.  "
      "Remind me to call Dana.")))

(deftest shopping-items-test
  (testing "splits on commas and \"and\", and capitalizes each item"
    (are [text items] (= items (classify/shopping-items text))
      "Remind me to add pickles, tomatoes, and flour to my shopping list."
      ["Pickles" "Tomatoes" "Flour"]

      "Add milk to my grocery list."
      ["Milk"]

      "add eggs and butter to the shopping list"
      ["Eggs" "Butter"]

      "Don't forget to add olive oil, salt and pepper to my shopping list."
      ["Olive oil" "Salt" "Pepper"]

      "Don’t forget to add sandwiches to my shopping list!"
      ["Sandwiches"]))

  (testing "nil when the whole transcript isn't a shopping request"
    (are [text] (nil? (classify/shopping-items text))
      "Remind me to add a note to the wiki."
      "Remind me to call Dana."
      "I told Dana to add milk to my shopping list."
      "Add milk to my shopping list and call Dana.")))

(def now (LocalDateTime/of 2026 11 2 10 0))

(deftest classify-test
  (testing "a reminder carries its title and the full transcript as :body"
    (is (= {:type  :reminder
            :title "Look into Ring software for Clojure"
            :body  "Remind me to look into Ring software for Clojure."}
           (classify/classify nil now "Remind me to look into Ring software for Clojure."))))

  (testing "a shopping request carries its items, even when it starts like a reminder"
    (is (= {:type  :shopping
            :items ["Pickles" "Flour"]
            :body  "Remind me to add pickles and flour to my shopping list."}
           (classify/classify nil now "Remind me to add pickles and flour to my shopping list."))))

  (testing "anything else is a note"
    (is (= {:type  :note
            :title "Idea: group memos by project"
            :body  "Idea: group memos by project. Maybe use tags."}
           (classify/classify nil now "Idea: group memos by project. Maybe use tags."))))

  (testing "a reminder with a time gets :due, and its title omits the phrase"
    (is (= {:type  :reminder
            :title "Call Dana"
            :body  "Remind me to call Dana tomorrow at 3."
            :due   (LocalDateTime/of 2026 11 3 15 0)}
           (classify/classify nil now "Remind me to call Dana tomorrow at 3."))))

  (testing "a reminder with only a date gets a LocalDate, meaning all-day"
    (is (= {:type  :reminder
            :title "Stretch"
            :body  "Remind me tomorrow to stretch."
            :due   (LocalDate/of 2026 11 3)}
           (classify/classify nil now "Remind me tomorrow to stretch."))))

  (testing "the title keeps the phrase when nothing else is left"
    (is (= "Tomorrow" (:title (classify/classify nil now "Remind me tomorrow.")))))

  (testing "a reminder naming a list gets :destination, and keeps the full :body"
    (is (= {:type        :reminder
            :title       "Do the dishes"
            :body        "Home list, remind me to do the dishes."
            :destination {:list "Home"}}
           (classify/classify {:reminders reminder-routes} now
                              "Home list, remind me to do the dishes."))))

  (testing "naming a list is enough to make a reminder"
    (is (= {:type        :reminder
            :title       "Do the dishes"
            :body        "Home list: do the dishes."
            :destination {:list "Home"}}
           (classify/classify {:reminders reminder-routes} now "Home list: do the dishes."))))

  (testing "a routed reminder still reads its due date"
    (is (= {:type        :reminder
            :title       "Do the dishes"
            :body        "Home list. Remind me to do the dishes tomorrow at 3."
            :due         (LocalDateTime/of 2026 11 3 15 0)
            :destination {:list "Home"}}
           (classify/classify {:reminders reminder-routes} now
                              "Home list. Remind me to do the dishes tomorrow at 3."))))

  (testing "a reminder naming no list gets no :destination"
    (is (not (contains? (classify/classify {:reminders reminder-routes} now "Remind me to call Dana.")
                        :destination))))

  (testing "a note starting with a keyword gets :destination, and its title omits it"
    (is (= {:type        :note
            :title       "Call the auditor"
            :body        "Acme note: call the auditor."
            :destination {:account "Acme"}}
           (classify/classify {:notes note-routes} now "Acme note: call the auditor."))))

  (testing "a list prefix is a reminder even when its name is also a note keyword"
    (is (= :reminder
           (:type (classify/classify {:notes {"Home" {:account "Home"}} :reminders reminder-routes}
                                     now "Home list, do the dishes.")))))

  (testing "a reminder with a priority phrase gets :priority, and keeps the full :body"
    (is (= {:type     :reminder
            :title    "Call Dana"
            :body     "Remind me to call Dana, high priority."
            :priority 1}
           (classify/classify nil now "Remind me to call Dana, high priority."))))

  (testing "a priority phrase can open a reminder"
    (are [text] (= [:reminder "Call Dana" 1]
                   ((juxt :type :title :priority) (classify/classify nil now text)))
      "High priority, remind me to call Dana."
      "Urgent, remind me to call Dana."
      "Urgent. Remind me to call Dana."))

  (testing "priority works alongside a due date and a list"
    (is (= {:type        :reminder
            :title       "Do the dishes"
            :body        "Home list, high priority, do the dishes tomorrow at 3."
            :due         (LocalDateTime/of 2026 11 3 15 0)
            :priority    1
            :destination {:list "Home"}}
           (classify/classify {:reminders reminder-routes} now
                              "Home list, high priority, do the dishes tomorrow at 3."))))

  (testing "a reminder with no priority phrase gets no :priority"
    (is (not (contains? (classify/classify nil now "Remind me to call Dana.") :priority))))

  (testing "a note keeps its words, and never gets :priority"
    (is (= {:type  :note
            :title "Urgent matters for the board"
            :body  "Urgent matters for the board."}
           (classify/classify nil now "Urgent matters for the board."))))

  (testing "a note never gets :due"
    (is (= {:type  :note
            :title "Idea for tomorrow: group memos"
            :body  "Idea for tomorrow: group memos."}
           (classify/classify nil now "Idea for tomorrow: group memos.")))))
