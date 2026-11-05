(ns memo-router.shopping
  (:require [babashka.process :as p]))

(defn add-item!
  "Sends item to shortcut named by (:shortcut config), which adds it to the shopping list. Returns nil; throws ex-info (:exit :err :item) on failure."
  [{:keys [bin shortcut] :or {bin "/usr/bin/shortcuts"}} item]
  (let [{:keys [exit err]} (p/sh {:in item} bin "run" shortcut)]
    (when-not (zero? exit)
      (throw (ex-info "shortcuts failed" {:exit exit :err err :item item})))))

(defn add-items!
  "Adds each of {:items memo} in turn, stopping at the first failure."
  [config memo]
  (run! #(add-item! config %) (:items memo)))
