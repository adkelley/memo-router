(ns memo-router.due
  (:require [clojure.string :as str])
  (:import (java.time DayOfWeek LocalDateTime LocalTime)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (java.time.temporal TemporalAdjusters)))

(def ^:private memo-path-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd/HH-mm-ss"))

(defn memo-time
  "Returns the LocalDateTime a memo was recorded, read from its path relative
  to the memo folder (e.g. 2026-11-02/18-30-12.m4a), or nil if the path has a
  different shape."
  [rel-path]
  (try
    (LocalDateTime/parse (str/replace rel-path #"\.m4a$" "") memo-path-format)
    (catch DateTimeParseException _ nil)))

(def ^:private day-pattern
  #"(?i)\b(?:(?:on|this|next)\s+)?(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b(?!['’])(?:\s+(?:in\s+the\s+)?(morning|afternoon|evening|night)\b)?")

(def ^:private part-pattern
  #"(?i)\b(?:tonight|(?:this|in\s+the)\s+(morning|afternoon|evening))\b")

(def ^:private relative-pattern
  #"(?i)\bin\s+(an?|\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(minute|hour|day)s?\b")

(def ^:private part-times
  {"morning"   (LocalTime/of 9 0)
   "afternoon" (LocalTime/of 14 0)
   "evening"   (LocalTime/of 18 0)
   "night"     (LocalTime/of 20 0)})

(def ^:private number-words
  {"a" 1 "an" 1 "one" 1 "two" 2 "three" 3 "four" 4 "five" 5 "six" 6
   "seven" 7 "eight" 8 "nine" 9 "ten" 10 "eleven" 11 "twelve" 12})

(defn- day-date [now word]
  (let [today (.toLocalDate now)
        word (str/lower-case word)]
    (case word
      "today" today
      "tomorrow" (.plusDays today 1)
      (.with today (TemporalAdjusters/next (DayOfWeek/valueOf (str/upper-case word)))))))

(def ^:private clock-pattern
  #"(?i)\bat\s+(\d{1,2})(?:[:.]([0-5]\d))?\s*([ap])\.?m\b\.?")

(def ^:private bare-clock-pattern
  #"(?i)\bat\s+(\d{1,2})(?:[:.]([0-5]\d))?(?:\s+o['’]?clock\b|(?=\s*(?:$|[.,!?](?!\d)|to\b)))")

(defn- clock-time [hour minute meridiem]
  (let [hour (parse-long hour)]
    (when (<= 1 hour 12)
      (LocalTime/of (+ (mod hour 12) (if (= "p" (str/lower-case meridiem)) 12 0))
                    (if minute (parse-long minute) 0)))))

(defn- without [text phrase]
  (-> text
      (str/replace-first phrase "")
      (str/replace #"\s+" " ")
      (str/replace #"\s+([.,!?])" "$1")
      (str/trim)))

(defn- amount [word]
  (or (number-words (str/lower-case word)) (parse-long word)))

(defn- part-time [word]
  (when word (part-times (str/lower-case word))))

(defn- find-relative [now text]
  (when-let [[phrase word unit] (re-find relative-pattern text)]
    (let [n    (amount word)
          text (without text phrase)]
      (case (str/lower-case unit)
        "minute" {:due (.plusMinutes now n) :text text}
        "hour"   {:due (.plusHours now n) :text text}
        "day"    {:date (.plusDays (.toLocalDate now) n) :text text}))))

(defn- find-day [now text]
  (when-let [[phrase word part] (re-find day-pattern text)]
    {:date (day-date now word) :part (part-time part) :text (without text phrase)}))

(defn- find-part [text]
  (when-let [[phrase part] (re-find part-pattern text)]
    {:part (part-time (or part "night")) :text (without text phrase)}))

(defn- nearest-time [part times]
  (apply min-key #(Math/abs (- (.getHour %) (.getHour part))) times))

(defn- pick-times [part clock-times]
  (cond
    (and part clock-times) [(nearest-time part clock-times)]
    part                   [part]
    :else                  clock-times))

(defn- find-explicit-clock [text]
  (when-let [[phrase hour minute meridiem] (re-find clock-pattern text)]
    (when-let [time (clock-time hour minute meridiem)]
      {:times [time] :text (without text phrase)})))

(defn- find-bare-clock [text]
  (when-let [[phrase hour minute] (re-find bare-clock-pattern text)]
    (when-let [am (clock-time hour minute "a")]
      {:times [am (clock-time hour minute "p")] :text (without text phrase)})))

(defn- find-clock [text]
  (or (find-explicit-clock text) (find-bare-clock text)))

(defn- daytime? [date-time]
  (<= 7 (.getHour date-time) 18))

(defn- ahead-on [now date times]
  (filter #(.isAfter % now) (map #(.atTime date %) times)))

(defn- choose-time
  "Picks from times on date: those still ahead of now if there are any, and
  of those the daytime one if there is one."
  [now date times]
  (let [ahead (ahead-on now date times)
        pool  (if (seq ahead) ahead (map #(.atTime date %) times))]
    (or (first (filter daytime? pool)) (first pool))))

(defn- next-date
  "Today if any of times is still ahead of now, otherwise tomorrow."
  [now times]
  (let [today (.toLocalDate now)]
    (if (seq (ahead-on now today times)) today (.plusDays today 1))))

(defn extract-due
  "Returns {:due <date or date-time> :text <text without the phrase>} for the
  date and time phrases in text, read relative to now, or nil if there are
  none. A LocalDate means an all-day reminder."
  [now text]
  (let [{:keys [due date part] :as day} (or (find-relative now text)
                                            (find-day now text)
                                            (find-part text))
        clock (find-clock (:text day text))
        text  (:text clock (:text day text))
        times (pick-times part (:times clock))]
    (cond
      due              {:due due :text (:text day)}
      (and date times) {:due (choose-time now date times) :text text}
      date             {:due date :text text}
      times            {:due (choose-time now (next-date now times) times) :text text})))
