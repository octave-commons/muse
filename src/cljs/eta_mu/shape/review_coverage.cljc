;; SPDX-License-Identifier: GPL-3.0-or-later
(ns eta-mu.shape.review-coverage
  "Pure delivery/assessment projection, independent of admission policy.")

(defn component-coverage
  "Describe delivery/assessment observations using the same closure in domain
   state and independent trace checks; empty roles never invent page IDs."
  [pages components delivered assessed empty-observed]
  (let [ids (set (map :id pages))
        base {:chunks (count ids) :delivered (count delivered) :assessed (count assessed)
              :missing (vec (sort (remove assessed ids)))}]
    (if (nil? components) base
      (assoc base :roles
             (into {} (map (fn [{:keys [role bytes sha256]}]
                             (let [role-ids (set (map :id (filter #(= role (:role %)) pages)))]
                               [(keyword role) {:bytes bytes :sha256 sha256 :chunks (count role-ids)
                                      :delivered (count (filter delivered role-ids))
                                      :assessed (count (filter assessed role-ids))
                                      :missing (vec (sort (remove assessed role-ids)))
                                      :empty? (zero? bytes)
                                      :empty-observed? (contains? empty-observed role)}])) components))))))
