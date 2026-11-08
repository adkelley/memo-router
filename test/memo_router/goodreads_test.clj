(ns memo-router.goodreads-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [memo-router.goodreads :as goodreads]))

(def ^:private base "https://www.goodreads.com/search?q=")

(deftest search-link-test
  (testing "drops the verb, a kind word and \"by\", keeping the author"
    (are [title query] (= (str base query) (goodreads/search-link title))
      "Read Dune"                                  "Dune"
      "Look into Dune"                             "Dune"
      "Look up Dune"                               "Dune"
      "Check out Dune"                             "Dune"
      "Search for Dune"                            "Dune"
      "Find Dune"                                  "Dune"
      "Read the book Dune by Frank Herbert"        "Dune%20Frank%20Herbert"
      "Check out the novel Piranesi"               "Piranesi"
      "Look into book Piranesi by Susanna Clarke"  "Piranesi%20Susanna%20Clarke"))

  (testing "keeps \"from\", which is common in book titles"
    (is (= (str base "Tales%20from%20Earthsea")
           (goodreads/search-link "Read Tales from Earthsea"))))

  (testing "words that only start like a verb or kind are kept"
    (are [title query] (= (str base query) (goodreads/search-link title))
      "Read Readers' Digest"        "Readers%27%20Digest"
      "Read Bookshop Memories"      "Bookshop%20Memories"
      "Look into Novelist's Guide"  "Novelist%27s%20Guide")))
