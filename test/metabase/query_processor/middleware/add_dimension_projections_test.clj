(ns metabase.query-processor.middleware.add-dimension-projections-test
  (:require [clojure.test :refer :all]
            #_[metabase.query-processor.build :as qp.build]
            [metabase.query-processor.middleware.add-dimension-projections :as add-dim-projections]
            [metabase.test.fixtures :as fixtures]
            [toucan.hydrate :as hydrate]))

(use-fixtures :once (fixtures/initialize :db))

;;; ----------------------------------------- add-fk-remaps (pre-processing) -----------------------------------------

(def ^:private example-query
  {:database 1
   :type     :query
   :query    {:source-table 1
              :fields       [[:field-id 1]
                             [:field-id 2]
                             [:field-id 3]]}})

(defn- do-with-fake-remappings-for-field-3 [f]
  (with-redefs [add-dim-projections/fields->field-id->remapping-dimension
                (constantly
                 {3 {:name "Product", :field_id 3, :human_readable_field_id 4}})]
    (f)))

(deftest create-remap-col-tuples
  (testing "make sure we create the remap column tuples correctly"
    (do-with-fake-remappings-for-field-3
     (fn []
       (is (= [[[:field-id 3]
                [:fk-> [:field-id 3] [:field-id 4]]
                {:name "Product", :field_id 3, :human_readable_field_id 4}]]
              (#'add-dim-projections/create-remap-col-tuples [[:field-id 1] [:field-id 2] [:field-id 3]])))))))

(deftest add-fk-remaps-test
  (do-with-fake-remappings-for-field-3
   (fn []
     (testing "make sure FK remaps add an entry for the FK field to `:fields`, and returns a pair of [dimension-info updated-query]"
       (is (= [[{:name "Product", :field_id 3, :human_readable_field_id 4}]
               (update-in example-query [:query :fields]
                          conj [:fk-> [:field-id 3] [:field-id 4]])]
              (#'add-dim-projections/add-fk-remaps example-query))))

     (testing "adding FK remaps should replace any existing order-bys for a field with order bys for the FK remapping Field"
       (is (= [[{:name "Product", :field_id 3, :human_readable_field_id 4}]
               (-> example-query
                   (assoc-in [:query :order-by] [[:asc [:fk-> [:field-id 3] [:field-id 4]]]])
                   (update-in [:query :fields]
                              conj [:fk-> [:field-id 3] [:field-id 4]]))]
              (#'add-dim-projections/add-fk-remaps (assoc-in example-query [:query :order-by] [[:asc [:field-id 3]]]))))))))


;;; ---------------------------------------- remap-results (post-processing) -----------------------------------------

(def ^:private col-defaults
  {:description     nil
   :source          :fields
   :fk_field_id     nil
   :visibility_type :normal
   :target          nil
   :remapped_from   nil
   :remapped_to     nil})

(def ^:private example-result-cols-id
  (merge
   col-defaults
   {:table_id     4
    :schema_name  "PUBLIC"
    :special_type :type/PK
    :name         "ID"
    :id           12
    :display_name "ID"
    :base_type    :type/BigInteger}))

(def ^:private example-result-cols-name
  (merge
   col-defaults
   {:table_id     4
    :schema_name  "PUBLIC"
    :special_type :type/Name
    :name         "NAME"
    :id           15
    :display_name "Name"
    :base_type    :type/Text}))

(def ^:private example-result-cols-category-id
  (merge
   col-defaults
   {:table_id     4
    :schema_name  "PUBLIC"
    :special_type :type/FK
    :name         "CATEGORY_ID"
    :id           11
    :display_name "Category ID"
    :base_type    :type/Integer}))

(def ^:private example-result-cols-price
  (merge
   col-defaults
   {:table_id     4
    :schema_name  "PUBLIC"
    :special_type :type/Category
    :name         "PRICE"
    :id           16
    :display_name "Price"
    :base_type    :type/Integer}))

;; test that internal get the appropriate values and columns injected in, and the `:remapped_from`/`:remapped_to` info
(def ^:private example-result-cols-foo
  {:description     nil
   :table_id        nil
   :name            "Foo"
   :remapped_from   "CATEGORY_ID"
   :remapped_to     nil
   :id              nil
   :target          nil
   :display_name    "Foo"})

(defn- add-remapping [query metadata rows]
  #_((qp.build/sync-query-processor
    (qp.build/async-query-processor
     (qp.build/base-query-processor
      (fn [_ _ _ return-results]
        (return-results metadata rows))
      [add-dim-projections/add-remapping])))
   query))

(def ^:private example-result-cols-category
  (merge
   col-defaults
   {:description     "The name of the product as it should be displayed to customers."
    :table_id        3
    :schema_name     nil
    :special_type    :type/Category
    :name            "CATEGORY"
    :fk_field_id     32
    :id              27
    :visibility_type :normal
    :display_name    "Category"
    :base_type       :type/Text}))

(deftest add-remapping-test
  (testing "remapping columns with `human_readable_values`"
    ;; swap out `hydrate` with one that will add some fake dimensions and values for CATEGORY_ID.
    (with-redefs [hydrate/hydrate (fn [fields & _]
                                    (for [{field-name :name, :as field} fields]
                                      (cond-> field
                                        (= field-name "CATEGORY_ID")
                                        (assoc :dimensions {:type :internal, :name "Foo", :field_id 10}
                                               :values     {:human_readable_values ["Foo" "Bar" "Baz" "Qux"]
                                                            :values                [4 11 29 20]}))))]
      (is (= {:status    :completed
              :row_count 5
              :data      {:rows [[1 "Red Medicine"                  4 3 "Foo"]
                                 [2 "Stout Burgers & Beers"        11 2 "Bar"]
                                 [3 "The Apple Pan"                11 2 "Bar"]
                                 [4 "Wurstküche"                   29 2 "Baz"]
                                 [5 "Brite Spot Family Restaurant" 20 2 "Qux"]]
                          :cols [example-result-cols-id
                                 example-result-cols-name
                                 (assoc example-result-cols-category-id
                                        :remapped_to "Foo")
                                 example-result-cols-price
                                 example-result-cols-foo]}}
             (with-redefs [add-dim-projections/add-fk-remaps (fn [query]
                                                               [nil query])]
               (add-remapping
                {}
                {:cols [example-result-cols-id
                        example-result-cols-name
                        example-result-cols-category-id
                        example-result-cols-price]}
                [[1 "Red Medicine"                  4 3]
                 [2 "Stout Burgers & Beers"        11 2]
                 [3 "The Apple Pan"                11 2]
                 [4 "Wurstküche"                   29 2]
                 [5 "Brite Spot Family Restaurant" 20 2]]))))))

  (testing "test that external remappings get the appropriate `:remapped_from`/`:remapped_to` info"
    (is (= {:status    :completed
            :row_count 0
            :data      {:rows []
                        :cols [example-result-cols-id
                               example-result-cols-name
                               (assoc example-result-cols-category-id
                                      :remapped_to "CATEGORY")
                               example-result-cols-price
                               (assoc example-result-cols-category
                                      :remapped_from "CATEGORY_ID"
                                      :display_name  "My Venue Category")]}}
           (with-redefs [add-dim-projections/add-fk-remaps (fn [query]
                                                             [[{:name "My Venue Category", :field_id 11, :human_readable_field_id 27}]
                                                              query])]
             (add-remapping
              {}
              {:cols [example-result-cols-id
                      example-result-cols-name
                      example-result-cols-category-id
                      example-result-cols-price
                      example-result-cols-category]}
              []))))))
