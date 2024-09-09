(ns nexus.authenticator
  (:require [nexus.crypto :as crypto]
            [slingshot.slingshot :refer [throw+]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))


(defprotocol ISignatureValidator
  (sign               [_ signer msg])
  (validate-signature [_ signer msg sig]))

(defrecord Authenticator [key-map verbose]

  ISignatureValidator

  (sign [_ signer msg]
    (let [key (get key-map signer)]
      (if key
        (crypto/generate-signature key msg)
        (throw+ {:type   ::missing-key
                 :signer signer}))))

  (validate-signature [_ signer msg sig]
    (let [key (get key-map signer)]
      (if key
        (let [result (crypto/validate-signature key msg sig)]
          (when verbose
            (println (format "signature for host %s valid: %s" signer result)))
          result)
        (do (println (format "unable to find key for %s in keys %s" signer (keys key-map)))
            (throw+ {:type   ::missing-key
                     :signer signer}))))))

(defn- read-key-collection-file [filename]
  (with-open [file (io/reader filename)]
    (json/read file { :key-fn keyword })))

(defn- decode-keys [key-col]
  (into {} (for [[signer key] key-col]
             [signer (crypto/decode-key key)])))

(defn make-authenticator [client-map verbose]
  (Authenticator. (decode-keys client-map) verbose))

(defn initialize-key-collection [filename verbose]
  (-> filename
      (read-key-collection-file)
      (make-authenticator verbose)))
