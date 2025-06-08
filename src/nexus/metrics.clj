(ns nexus.metrics
  (:require [metrics.timers :as timers]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [nexus.logging :as log]
            [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [iapetos.export :as export]))

(defn initialize-metrics []
  (log/info! "Initializing Nexus metrics")  
  (-> (prometheus/collector-registry)
      (jvm/initialize)
      (ring/initialize)
      (counters/counter "error-counter")))

(defn metrics-handler [registry]
  (export/text-format registry))

(defn time-request [registry]
  (fn [handler]
    (fn [request]
      (timers/time! (timers/timer registry "request-timer")
        (try
          (let [response (handler request)]
            (when-let [req-size (get-in request [:headers "content-length"])]
              (histograms/update! (histograms/histogram registry "request-size") (Long/parseLong req-size)))
            (when-let [res-size (get-in response [:headers "content-length"])]
              (histograms/update! (histograms/histogram registry "response-size") (Long/parseLong res-size)))
            response)
          (catch Exception e
            (log/warn! e "Error in timed request")
            (counters/inc! registry "error-counter")
            (throw e)))))))
