(ns metabase.query-processor.middleware.add-timezone-info
  (:require [metabase.query-processor.timezone :as qp.timezone]))

(defn- add-timezone-metadata [metadata]
  (merge
   metadata
   {:results_timezone (qp.timezone/results-timezone-id)}
   (when-let [requested-timezone-id (qp.timezone/requested-timezone-id)]
     {:requested_timezone requested-timezone-id})))

(defn add-timezone-info
  "Add `:results_timezone` and `:requested_timezone` info to query results."
  [qp]
  (fn [query xformf {:keys [metadataf], :as context}]
    (qp query xformf (assoc context :metadataf (comp metadataf add-timezone-metadata)))))
