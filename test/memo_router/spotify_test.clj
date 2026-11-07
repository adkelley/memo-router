(ns memo-router.spotify-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [memo-router.spotify :as spotify]))

(def ^:private base "https://open.spotify.com/search/")

(deftest search-link-test
  (testing "drops the verb, a kind word, and \"by\" or \"from\""
    (are [title query] (= (str base query) (spotify/search-link title))
      "Look into Pink Floyd"                       "Pink%20Floyd"
      "Look up Pink Floyd"                         "Pink%20Floyd"
      "Listen to Pink Floyd"                       "Pink%20Floyd"
      "Check out Pink Floyd"                       "Pink%20Floyd"
      "Search for Pink Floyd"                      "Pink%20Floyd"
      "Find Pink Floyd"                            "Pink%20Floyd"
      "Look into the album Animals by Pink Floyd"  "Animals%20Pink%20Floyd"
      "Look into the song Time by Pink Floyd"      "Time%20Pink%20Floyd"
      "Look into track Time"                       "Time"
      "Check out the band Radiohead"               "Radiohead"
      "Look into the artist Bjork"                 "Bjork"
      "Look into Wish You Were Here by Pink Floyd" "Wish%20You%20Were%20Here%20Pink%20Floyd"
      "Look into Comfortably Numb from The Wall"   "Comfortably%20Numb%20The%20Wall"
      "LOOK INTO THE ALBUM Animals BY Pink Floyd"  "Animals%20Pink%20Floyd"))

  (testing "a title with no verb is searched as it is"
    (is (= (str base "Pink%20Floyd") (spotify/search-link "Pink Floyd"))))

  (testing "words that only look like a verb, kind or \"by\" are kept"
    (are [title query] (= (str base query) (spotify/search-link title))
      "Finding Nemo soundtrack"   "Finding%20Nemo%20soundtrack"
      "Look into Abbey Road"      "Abbey%20Road"
      "Look into the Bandwagon"   "the%20Bandwagon"))

  (testing "encodes accents and symbols, with %20 for spaces"
    (are [title query] (= (str base query) (spotify/search-link title))
      "Look into Sigur Rós" "Sigur%20R%C3%B3s"
      "Look into Simon & Garfunkel" "Simon%20%26%20Garfunkel"))

  (testing "adds no artists, albums or tracks filter, which the iPhone app misreads"
    (is (= (str base "Animals%20Pink%20Floyd")
           (spotify/search-link "Look into the album Animals by Pink Floyd")))))
