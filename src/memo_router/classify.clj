(ns memo-router.classify
  (:require [clojure.string :as str]))

(def reminder-pattern #"(?i)^\s*(remind me( to)?|reminder:?|don[’']t forget( to)?)\b:?\s*")

(defn reminder? [text]
  (some? (re-find reminder-pattern text)))

(defn- capitalize-first [s]
  (if (str/blank? s)
    s
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn- clean-title [s]
  (-> s
      (str/replace #"\s*[.!?]$" "")
      capitalize-first))

(defn reminder-title [text]
  (-> text
      (str/replace reminder-pattern "")
      clean-title))


(defn note-title [text]
  (-> (str/split text #"(?<=[.!?])\s+")
      first
      clean-title))          ; strip end punctuation, trim, capitalise

(defn classify [text]
  (cond
    (reminder? text) {:type :reminder :title (reminder-title text) :body text}
    :else {:type :note :title (note-title text) :body text}))
