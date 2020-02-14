(ns metabase.query-processor.streaming.json
  (:require [cheshire.core :as json]
            [metabase.query-processor.streaming.interface :as i])
  (:import java.io.Writer))

(defmethod i/stream-options :json
  [_]
  {:content-type "applicaton/json; charset=utf-8"})

(defn- map->serialized-json-kvs
  "{:a 100, :b 200} ; -> \"a\":100,\"b\":200"
  ^String [m]
  (when (seq m)
    (let [s (json/generate-string m)]
      (.substring s 1 (dec (count s))))))

(defmethod i/streaming-results-writer :json
  [_ ^Writer writer]
  (reify i/StreamingResultsWriter
    (begin! [_ _]
      (.write writer "{\"data\":{\"rows\":[\n"))

    (write-row! [_ row row-num]
      (when-not (zero? row-num)
        (.write writer ",\n"))
      (json/generate-stream row writer)
      (.flush writer))

    (finish! [_ {:keys [data], :as metadata}]
      (let [data-kvs-str           (map->serialized-json-kvs data)
            other-metadata-kvs-str (map->serialized-json-kvs (dissoc metadata :data))]
        ;; close data.rows
        (.write writer "\n]")
        ;; write any remaining keys in data
        (when (seq data-kvs-str)
          (.write writer ",\n")
          (.write writer data-kvs-str))
        ;; close data
        (.write writer "}")
        ;; write any remaining top-level keys
        (when (seq other-metadata-kvs-str)
          (.write writer ",\n")
          (.write writer other-metadata-kvs-str))
        ;; close top-level map
        (.write writer "}"))
      (.close writer))))
