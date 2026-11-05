(ns memo-router.due-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [memo-router.due :as due]))

;; Monday 2 November 2026, at three times of day. Each case below says which
;; one it uses, because bare hours depend on what is still ahead.
(def morning (due/memo-time "2026-11-02/10-00-00.m4a"))
(def afternoon (due/memo-time "2026-11-02/16-00-00.m4a"))
(def evening (due/memo-time "2026-11-02/18-30-00.m4a"))

(defn- due-at
  "The :due of text as a string, so dates and date-times compare the same way."
  [now text]
  (some-> (due/extract-due now text) :due str))

(defn- remaining-text [now text]
  (:text (due/extract-due now text)))

(deftest memo-time-test
  (testing "reads the recording time from the memo's relative path"
    (is (= "2026-11-02T18:30:12" (str (due/memo-time "2026-11-02/18-30-12.m4a")))))

  (testing "nil for a path of a different shape, or an impossible date"
    (are [path] (nil? (due/memo-time path))
      "notes/odd-name.m4a"
      "18-30-12.m4a"
      "2026-13-02/18-30-12.m4a")))

(deftest day-test
  (testing "today and tomorrow give an all-day date"
    (are [text due] (= due (due-at morning text))
      "Remind me to call Dana today."    "2026-11-02"
      "Remind me to call Dana tomorrow." "2026-11-03"
      "TOMORROW call Dana"               "2026-11-03"))

  (testing "a weekday is the next one after the memo's date"
    (are [text due] (= due (due-at morning text))
      "Remind me to pay rent Friday."      "2026-11-06"
      "Remind me to pay rent on Friday."   "2026-11-06"
      "Remind me to pay rent this Friday." "2026-11-06"
      "Remind me to pay rent next Friday." "2026-11-06"
      ;; the memo was recorded on a Monday, so this is a week away
      "Remind me to pay rent on Monday."   "2026-11-09"))

  (testing "a possessive or plural weekday is not a date"
    (are [text] (nil? (due/extract-due morning text))
      "Remind me to review Friday's report."
      "Remind me to review Friday’s report."
      "Remind me about Fridays.")))

(deftest explicit-time-test
  (testing "am and pm combine with a date, in either order"
    (are [text due] (= due (due-at evening text))
      "Remind me to call tomorrow at 3 pm."         "2026-11-03T15:00"
      "Remind me tomorrow at 3:30 p.m. to stretch." "2026-11-03T15:30"
      "Remind me on Friday at 9 AM to pay rent."    "2026-11-06T09:00"
      "Remind me at 12 pm Friday to eat."           "2026-11-06T12:00"
      "Remind me at 12 am tomorrow to sleep."       "2026-11-03T00:00"))

  (testing "with no date, today if still ahead, otherwise tomorrow"
    (are [text due] (= due (due-at evening text))
      "Remind me to call at 8 pm." "2026-11-02T20:00"
      "Remind me to call at 9 am." "2026-11-03T09:00"))

  (testing "a dated time already past is kept as spoken"
    (is (= "2026-11-02T09:00" (due-at morning "Remind me to call today at 9 am."))))

  (testing "minutes can follow a dot, as Whisper sometimes writes them"
    (are [text due] (= due (due-at afternoon text))
      "Remind me to call today at 4.55."    "2026-11-02T16:55"
      "Remind me to call at 4.55 pm."       "2026-11-02T16:55"
      "Remind me to call at 4.55 p.m."      "2026-11-02T16:55"))

  (testing "an impossible time is not a time"
    (are [text] (nil? (due/extract-due morning text))
      "Remind me at 13 pm to call."
      "Remind me to call at 30."
      "Remind me to call at 4.75.")))

(deftest bare-hour-test
  (testing "the next occurrence, preferring daytime when both are ahead"
    (are [now text due] (= due (due-at now text))
      morning   "Remind me to call at 3."           "2026-11-02T15:00"
      morning   "Remind me to call at 9."           "2026-11-02T21:00"
      afternoon "Remind me to call at 3."           "2026-11-03T15:00"
      evening   "Remind me to call at 8."           "2026-11-02T20:00"
      morning   "Remind me tomorrow at 9 to call."  "2026-11-03T09:00"
      morning   "Remind me to call at 3 tomorrow."  "2026-11-03T15:00"
      morning   "Remind me at 3:30 to stretch."     "2026-11-02T15:30"))

  (testing "o'clock marks a time, whatever follows it"
    (are [text due] (= due (due-at morning text))
      "Remind me to call at 3 o'clock."            "2026-11-02T15:00"
      "Remind me to call at 3 o’clock."            "2026-11-02T15:00"
      "Remind me to call at 3 oclock."             "2026-11-02T15:00"
      "Remind me tomorrow at 9 o'clock to call."   "2026-11-03T09:00"
      "Remind me at 3 o'clock about the meeting."  "2026-11-02T15:00")
    (is (= "Remind me about the meeting."
           (remaining-text morning "Remind me at 3 o'clock about the meeting."))))

  (testing "a number that is not a time stays in the text"
    (is (nil? (due/extract-due morning "Remind me to look at 3 options.")))
    (is (= "2026-11-03" (due-at morning "Remind me tomorrow to look at 3 options.")))
    (is (= "Remind me to look at 3 options."
           (remaining-text morning "Remind me tomorrow to look at 3 options.")))))

(deftest relative-test
  (testing "minutes and hours count from the memo's time"
    (are [text due] (= due (due-at morning text))
      "Remind me in 2 hours to call."       "2026-11-02T12:00"
      "Remind me to stretch in 30 minutes." "2026-11-02T10:30"
      "Remind me in an hour to stretch."    "2026-11-02T11:00"
      "Remind me in two hours to stretch."  "2026-11-02T12:00"))

  (testing "days give an all-day date, or combine with a time"
    (are [text due] (= due (due-at morning text))
      "Remind me in 3 days to call."        "2026-11-05"
      "Remind me in a day at 3 pm to call." "2026-11-03T15:00"))

  (testing "a vague amount is not a date"
    (is (nil? (due/extract-due morning "Remind me to check in a few days.")))))

(deftest part-of-day-test
  (testing "after a date, or alone"
    (are [now text due] (= due (due-at now text))
      morning "Remind me to call Thursday afternoon."       "2026-11-05T14:00"
      morning "Remind me tomorrow morning to call."         "2026-11-03T09:00"
      morning "Remind me on Friday in the evening to call." "2026-11-06T18:00"
      morning "Remind me tomorrow night to call."           "2026-11-03T20:00"
      morning "Remind me to call tonight."                  "2026-11-02T20:00"
      morning "Remind me this afternoon to call."           "2026-11-02T14:00"
      evening "Remind me in the morning to call."           "2026-11-03T09:00"))

  (testing "a bare hour takes the side of the day that was spoken"
    (are [text due] (= due (due-at morning text))
      "Remind me tomorrow evening at 8 to call." "2026-11-03T20:00"
      "Remind me tomorrow morning at 8 to call." "2026-11-03T08:00"
      "Remind me tomorrow morning at 11 o'clock." "2026-11-03T11:00"))

  (testing "alone, without this or in the, it is an ordinary word"
    (is (nil? (due/extract-due morning "Remind me to take my morning pills.")))))

(deftest text-test
  (testing "removes the phrase, leaving clean spacing and punctuation"
    (are [text remaining] (= remaining (remaining-text morning text))
      "Remind me tomorrow to stretch."        "Remind me to stretch."
      "Remind me to call Dana tomorrow."      "Remind me to call Dana."
      "Remind me to call at 3, then stretch." "Remind me to call, then stretch."
      "Remind me to call at 4.55."            "Remind me to call."
      "tomorrow call Dana"                    "call Dana"))

  (testing "the first date wins; a repeat stays in the text"
    (is (= "2026-11-03" (due-at morning "Remind me tomorrow to stretch tomorrow.")))
    (is (= "Remind me to stretch tomorrow."
           (remaining-text morning "Remind me tomorrow to stretch tomorrow."))))

  (testing "nil when there is no date or time"
    (is (nil? (due/extract-due morning "Remind me to call Dana.")))))
