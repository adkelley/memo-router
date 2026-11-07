(ns memo-router.links-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [memo-router.links :as links]))

(def spotify-reminder
  {:type        :reminder
   :title       "Look into Pink Floyd"
   :body        "Spotify list, remind me to look into Pink Floyd."
   :destination {:list "Spotify" :link :spotify}})

(deftest link-test
  (testing "a known service builds its link"
    (is (= "https://open.spotify.com/search/Pink%20Floyd"
           (links/link :spotify "Look into Pink Floyd"))))

  (testing "an unknown service gives nil rather than throwing"
    (is (nil? (links/link :goodreads "Look into Dune")))
    (is (nil? (links/link nil "Look into Dune")))))

(deftest add-link-test
  (testing "adds :link when the destination names a service"
    (is (= (assoc spotify-reminder :link "https://open.spotify.com/search/Pink%20Floyd")
           (links/add-link spotify-reminder))))

  (testing "returns the memo unchanged otherwise"
    (doseq [memo [(assoc spotify-reminder :destination {:list "Home"})
                  (dissoc spotify-reminder :destination)
                  (assoc-in spotify-reminder [:destination :link] :goodreads)
                  {:type :note :title "Idea" :body "Idea."}
                  {:type :shopping :items ["Milk"] :body "Add milk to my shopping list."}]]
      (is (= memo (links/add-link memo))))))
