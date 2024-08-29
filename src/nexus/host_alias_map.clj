(ns nexus.host-alias-map
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn read-json-file [filename]
  (with-open [file (io/reader filename)]
    (json/read file { :key-fn keyword })))

(defn make-domain-fqdns [{:keys [domain aliases]}]
  (map (fn [alias] (format "%s.%s" alias domain)) aliases))

(defn make-host-pairs [[host domain-aliases]]
  (->> domain-aliases
       (mapcat make-domain-fqdns)
       (map (fn [fqdn] [fqdn host]))))

(defprotocol IHostAliasMap
  (get-host [self host domain]))

(defrecord HostAliasMap [alias-map]
  IHostAliasMap
  (get-host [_ host domain] (get alias-map
                                 (format "%s.%s" (name host) (name domain))
                                 (keyword host))))

(defn make-mapper [host-alias-map-file]
  (if (string? host-alias-map-file)
    (->> host-alias-map-file
         (read-json-file)
         (into {} (mapcat make-host-pairs))
         (->HostAliasMap))
    (->HostAliasMap {})))
