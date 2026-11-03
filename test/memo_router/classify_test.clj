(ns memo-router.classify-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [memo-router.classify :as classify]))

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

(deftest classify-test
  (testing "a reminder carries its title and the full transcript as :body"
    (is (= {:type  :reminder
            :title "Look into Ring software for Clojure"
            :body  "Remind me to look into Ring software for Clojure."}
           (classify/classify nil "Remind me to look into Ring software for Clojure."))))

  (testing "anything else is a note"
    (is (= {:type  :note
            :title "Idea: group memos by project"
            :body  "Idea: group memos by project. Maybe use tags."}
           (classify/classify nil "Idea: group memos by project. Maybe use tags.")))))
