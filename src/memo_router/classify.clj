(ns memo-router.classify
  (:require [clojure.string :as str]
            [memo-router.due :as due]))

(def reminder-pattern #"(?i)^\s*(remind me( to)?|reminder:?|don[’']t forget( to)?)\b:?\s*")

(def ^:private priority-pattern
  #"(?i)[,.;]?\s*\b(?:(high|medium|low)[\s-]+priority|urgent(?:ly)?)\b[:,.]?(?=\s|$)")

(def ^:private priorities {"high" 1 "medium" 5 "low" 9})

(defn extract-priority
  "Returns {:priority <number> :text <text without the phrase>} for a phrase like
  \"high priority\" or \"urgent\" in text, or nil if there is none. The numbers
  are the ones the Reminders app sets: 1 high, 5 medium, 9 low."
  [text]
  (when-let [[phrase level] (re-find priority-pattern text)]
    {:priority (priorities (str/lower-case (or level "high")))
     :text     (due/without text phrase)}))

(defn- route-pattern [keyword suffix]
  (re-pattern (str "(?i)^\\s*" (java.util.regex.Pattern/quote keyword) suffix "[:,.]?\\s*")))

(defn- find-route
  "Returns {:destination <route> :text <text without the prefix>} for the first
  keyword in routes that starts text and is followed by suffix, longest keyword
  first, or nil."
  [suffix routes text]
  (some (fn [[keyword destination]]
          (let [pattern (route-pattern keyword suffix)]
            (when (re-find pattern text)
              {:destination destination
               :text (str/replace-first text pattern "")})))
        (sort-by (comp - count key) routes)))

(defn note-route
  "The route for a note starting with a keyword from routes, optionally followed
  by \"note\", as in \"Acme note: call the auditor\". See find-route."
  [routes text]
  (find-route "\\b\\s*(note)?\\b" routes text))

(defn reminder-route
  "The route for a reminder starting with a keyword from routes and \"list\", as
  in \"Home list, remind me to do the dishes\". See find-route."
  [routes text]
  (find-route "\\s+list\\b" routes text))

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

(defn- reminder-memo [routes now text]
  (let [{:keys [priority] :as ranked} (extract-priority text)
        spoken (:text ranked text)
        {:keys [destination] :as route} (reminder-route routes spoken)
        task  (:text route spoken)
        {:keys [due] :as found} (due/extract-due now task)
        title (reminder-title (:text found task))]
    (cond-> {:type :reminder
             :title (if (str/blank? title) (reminder-title task) title)
             :body text}
      due (assoc :due due)
      priority (assoc :priority priority)
      destination (assoc :destination destination))))

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
  the phrase. A reminder with a priority phrase (see extract-priority) also gets :priority.
  A reminder starting with a list from (:reminders routes) (see
  reminder-route), or a note starting with a keyword from (:notes routes) (see
  note-route), also gets :destination, and its title omits the prefix. routes
  may be nil."
  [{:keys [notes reminders]} now text]
  (let [items  (shopping-items text)
        spoken (:text (extract-priority text) text)]
    (cond
      items
      {:type :shopping :items items :body text}

      (or (reminder-route reminders spoken) (reminder? spoken))
      (reminder-memo reminders now text)

      :else
      (note-memo notes text))))

(defn noise?
  "True if a transcript has fewer than two words.  Whisper often invents a single
  word, like \"You\", from a recording of silence."
  [text]
  (< (count (str/split (str/trim text) #"\s+")) 2))
