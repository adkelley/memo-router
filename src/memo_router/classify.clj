(ns memo-router.classify
  (:require [clojure.string :as str]
            [memo-router.due :as due]))

(def reminder-pattern #"(?i)^\s*(remind me( to)?|reminder:?|don[’']t forget( to)?)\b:?\s*")

(defn- route-pattern [keyword]
  (re-pattern (str "(?i)^\\s*" (java.util.regex.Pattern/quote keyword) "\\b\\s*(note)?\\b[:,.]?\\s*")))

(defn note-route
  "Returns {:destination <route> :text <text without the prefix>} for the
  first keyword in routes that starts text, longest keyword first, or nil."
  [routes text]
  (some (fn [[keyword destination]]
          (let [pattern (route-pattern keyword)]
            (when (re-find pattern text)
              {:destination destination
               :text (str/replace-first text pattern "")})))
        (sort-by (comp - count key) routes)))

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

(def ^:private max-title-words 8)
(defn note-title [text]
  (let [words (->> (str/split text #"(?<=[.!?])\s+")
                   first
                   clean-title
                   (re-seq #"\S+"))   ; words, ignoring extra or leading whitespace
        title (str/join " " (take max-title-words words))]
    (if (> (count words) max-title-words)
      (str title "...")
      title)))

(def ^:private shopping-pattern
  #"(?i)^\s*(?:(?:remind me|don[’']t forget) to\s+)?add\s+(.+?)\s+to\s+(?:my|the)\s+(?:shopping|grocery)\s+list[.!?]?\s*$")

(defn shopping-items
  "Returns the items in a request like \"add pickles and mustard to my shopping list\"
  as a vector of capitalized strings, or nil if text isn't one."
  [text]
  (when-let [[_ items] (re-find shopping-pattern text)]
    (->> (str/split items #"\s*,\s*(?:and\s+)?|\s+and\s+")
         (remove str/blank?)
         (mapv capitalize-first))))

(defn- reminder-memo [now text]
  (let [{:keys [due] :as found} (due/extract-due now text)
        title (reminder-title (:text found text))]
    (cond-> {:type :reminder
             :title (if (str/blank? title) (reminder-title text) title)
             :body text}
      due (assoc :due due))))

(defn- note-memo [routes text]
  (let [{:keys [destination] :as route} (note-route routes text)]
    (cond-> {:type :note
             :title (note-title (:text route text))
             :body text}
      destination (assoc :destination destination))))

(defn classify
  "Classifies a transcript as a shopping request, a reminder or a note.
  A shopping request returns {:type :shopping :items :body}; the others return
  {:type :title :body}. A reminder with a date or time phrase (see
  due/extract-due, read relative to now) also gets :due, and its title omits
  the phrase. A note that starts with a keyword from routes (see note-route)
  also gets :destination, and its title omits the keyword. routes may be nil."
  [routes now text]
  (let [items (shopping-items text)]
    (cond
      items            {:type :shopping :items items :body text}
      (reminder? text) (reminder-memo now text)
      :else            (note-memo routes text))))

(defn noise?
  "True if a transcript has fewer than two words.  Whisper often invents a single
  word, like \"You\", from a recording of silence."
  [text]
  (< (count (str/split (str/trim text) #"\s+")) 2))
