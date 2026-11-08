(ns memo-router.search-terms-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [memo-router.search-terms :as terms]))

(def wording
  {:verbs   ["look into" "listen to"]
   :kinds   ["album" "song"]
   :joiners ["by" "from"]})

(deftest search-terms-test
  (testing "leaves out a leading verb, a kind word, and the joiners"
    (are [title terms] (= terms (terms/search-terms title wording))
      "Look into Pink Floyd"                      "Pink Floyd"
      "Listen to the song Time by Pink Floyd"     "Time Pink Floyd"
      "Look into album Animals"                   "Animals"
      "Look into Comfortably Numb from The Wall"  "Comfortably Numb The Wall"
      "LOOK INTO THE ALBUM Animals BY Pink Floyd" "Animals Pink Floyd"))

  (testing "a verb or kind word only counts at the start"
    (are [title terms] (= terms (terms/search-terms title wording))
      "Pink Floyd, look into it"         "Pink Floyd, look into it"
      "Look into Pink Floyd's album Animals" "Pink Floyd's album Animals"))

  (testing "words that only start like one are kept"
    (are [title terms] (= terms (terms/search-terms title wording))
      "Look into Albumen"       "Albumen"
      "Look into Abbey Road"    "Abbey Road"
      "Look into Songbird"      "Songbird"))

  (testing "a missing list skips that step"
    (is (= "the album Animals by Pink Floyd"
           (terms/search-terms "Look into the album Animals by Pink Floyd" {:verbs ["look into"]})))
    (is (= "Look into the album Animals by Pink Floyd"
           (terms/search-terms "Look into the album Animals by Pink Floyd" {}))))

  (testing "words are matched literally, not as regex"
    (is (= "Dune" (terms/search-terms "Look up. Dune" {:verbs ["look up."]})))
    (is (= "Look upX Dune" (terms/search-terms "Look upX Dune" {:verbs ["look up."]}))))

  (testing "trims what's left"
    (is (= "Pink Floyd" (terms/search-terms "Look into Pink Floyd  " {:verbs ["look into"]})))))

(deftest encode-test
  (testing "encodes spaces as %20, and escapes accents and symbols"
    (are [s encoded] (= encoded (terms/encode s))
      "Pink Floyd"         "Pink%20Floyd"
      "Sigur Rós"          "Sigur%20R%C3%B3s"
      "Simon & Garfunkel"  "Simon%20%26%20Garfunkel"
      "AC/DC"              "AC%2FDC"
      "What?"              "What%3F")))
