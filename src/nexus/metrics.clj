(ns nexus.metrics
  (:require [metrics.core :as metrics]
            [metrics.timers :as timers]
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
      (ring/initialize)))

(defn metrics-handler [registry]
  (export/text-format registry))

(defn time-request [handler]
  (fn [request]
    (timers/time! (metrics/timer "request-timer")
      (try
        (let [response (handler request)]
          (when-let [req-size (get-in request [:headers "content-length"])]
            (histograms/update! (metrics/histogram "request-size") (Long/parseLong req-size)))
          (when-let [res-size (get-in response [:headers "content-length"])]
            (histograms/update! (metrics/histogram "response-size") (Long/parseLong res-size)))
          response)
        (catch Exception e
          (log/warn! e "Error in timed request")
          (metrics/inc! (metrics/counter "error-counter"))
          (throw e))))))
