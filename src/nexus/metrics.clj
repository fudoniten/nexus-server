(ns nexus.metrics
  (:require [metrics.core :as metrics]
            [metrics.timers :as timers]
            [metrics.histograms :as histograms]))

(def registry (metrics/new-registry))

(def request-timer
  (timers/timer registry ["http" "requests"]))

(def error-counter
  (metrics/counter registry ["errors" "total"]))

(def request-size-histogram
  (histograms/histogram registry ["http" "request" "size"]))

(def response-size-histogram
  (histograms/histogram registry ["http" "response" "size"]))

(defn time-request [handler]
  (fn [request]
    (timers/time! request-timer
      (try
        (let [response (handler request)]
          (when-let [req-size (get-in request [:headers "content-length"])]
            (histograms/update! request-size-histogram (Long/parseLong req-size)))
          (when-let [res-size (get-in response [:headers "content-length"])]
            (histograms/update! response-size-histogram (Long/parseLong res-size)))
          response)
        (catch Exception e
          (metrics/inc! error-counter)
          (throw e))))))
