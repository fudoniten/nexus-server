(ns nexus.server
  (:require [reitit.ring :as ring]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [nexus.authenticator :as auth]
            [nexus.datastore :as store]
            [slingshot.slingshot :refer [try+]]
            [fudo-clojure.ip :as ip]
            [fudo-clojure.common :refer [current-epoch-timestamp parse-epoch-timestamp]]))

(defn pthru [o] (clojure.pprint/pprint o) o)

(defn- set-host-ipv4 [store]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (let [ip (ip/from-string payload)]
       (when (not (ip/ipv4? ip))
         {:status 400
          :body (format "rejected: not a v4 IP: %s" payload)})
       (store/set-host-ipv4 store domain host ip)
       {:status 200 :body (str ip)})
     (catch IllegalArgumentException _
       {:status 400
        :body (format "rejected: failed to parse IP: %s" payload)})
     (catch Exception e
       ;; FIXME: don't spill the beans
       {:status 500
        :body (format "an unknown error has occurred: %s"
                      (.toString e))}))))

(defn- set-host-ipv6 [store]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (let [ip (ip/from-string payload)]
       (when (not (ip/ipv6? ip))
         {:status 400
          :body (format "rejected: not a v6 IP: %s" payload)})
       (store/set-host-ipv4 store domain host ip)
       {:status 200 :body (str ip)})
     (catch IllegalArgumentException _
       {:status 400
        :body (format "rejected: failed to parse IP: %s" payload)})
     (catch Exception e
       ;; FIXME: don't spill the beans
       {:status 500
        :body (format "an unknown error has occurred: %s"
                      (.toString e))}))))

(defn- valid-sshfp? [sshfp]
  (not (nil? (re-matches #"^[12346] [12] [0-9a-fA-F ]{20,256}$" sshfp))))

(defn- set-host-sshfps [store]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (if (not (every? valid-sshfp? payload))
       {:status 400 :body (str "rejected: invalid sshfp: "
                               (some (comp not valid-sshfp?) payload))}
       (do (store/set-host-sshfps store domain host payload)
           {:status 200 :body payload}))
     (catch Exception e
       ;; FIXME: don't spill the beans
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- get-host-ipv4 [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ip (store/get-host-ipv4 store domain host)]
       (if ip
         {:status 200 :body (str ip)}
         {:status 404 :body (format "IPv4 for %s.%s not found." host domain)}))
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- get-host-ipv6 [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ip (store/get-host-ipv6 store domain host)]
       (if ip
         {:status 200 :body (str ip)}
         {:status 404 :body (format "IPv6 for %s.%s not found." host domain)}))
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- get-host-sshfps [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [sshfps (store/get-host-sshfps store domain host)]
       (if sshfps
         {:status 200 :body sshfps}
         {:status 404 :body (format "SSHFP for %s.%s not found." host domain)}))
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- decode-body [handler]
  (fn [{:keys [body] :as req}]
    (pthru req)
    (if body
      (let [body-str (slurp body)]
        (handler (-> req
                     (assoc :payload (json/read-str (pthru body-str)))
                     (assoc :body-str body-str))))
      (handler (-> req (assoc :body-str ""))))))

(defn- encode-body [handler]
  (fn [req]
    (let [resp (handler req)]
      (assoc resp :body (json/write-str (:body resp))))))

(defn- keywordize-headers [handler]
  (fn [req]
    (handler (update req :headers
                     (fn [headers] (update-keys headers keyword))))))

(defn- build-request-string [& {:keys [body method uri timestamp]}]
  (str (-> method (name) (str/upper-case))
       uri
       timestamp
       body))

(defn- authenticate-request [authenticator
                             {:keys [body-str request-method uri]
                              {:keys [host]} :path-params
                              {:keys [access-signature access-timestamp]} :headers}]
  (let [req-str (build-request-string :body body-str
                                      :method request-method
                                      :uri uri
                                      :timestamp access-timestamp)]
    (auth/validate-signature authenticator (keyword host) req-str access-signature)))

(defn- make-host-signature-authenticator [authenticator]
  (fn [handler]
    (fn [{{:keys [access-signature]} :headers
         :as req}]
      (if (nil? access-signature)
        { :status 406 :body "rejected: missing request signature" }
        (try+
         (if (authenticate-request authenticator req)
           (handler req)
           { :status 401 :body "rejected: request signature invalid" })
         (catch [:type ::auth/missing-key] _
             { :status 404 :body (str "rejected: missing key for host") }))))))

(defn- make-timing-validator [max-diff]
  (fn [handler]
    (fn [{{:keys [access-timestamp]} :headers
         :as req}]
      (if (nil? access-timestamp)
        { :status 406 :body "rejected: missing request timestamp" }
        (let [timestamp (-> access-timestamp
                            (Integer/parseInt)
                            (parse-epoch-timestamp)
                            (.getEpochSecond))
              current-timestamp (current-epoch-timestamp)
              time-diff (abs (- timestamp current-timestamp))]
          (if (> time-diff max-diff)
            { :status 412 :body "rejected: request timestamp out of date" }
            (handler req)))))))

(defn create-app [& {:keys [authenticator data-store max-delay]
                     :or   {max-delay 60}}]
  (ring/ring-handler
   (ring/router ["/api" {:middleware [keywordize-headers decode-body encode-body (make-timing-validator max-delay)]}
                 ["/:domain"
                  ["/:host" {:middleware [(make-host-signature-authenticator authenticator)]}
                   ["/ipv4" {:put {:handler (set-host-ipv4 data-store)}
                             :get {:handler (get-host-ipv4 data-store)}}]
                   ["/ipv6" {:put {:handler (set-host-ipv6 data-store)}
                             :get {:handler (get-host-ipv6 data-store)}}]
                   ["/sshfps" {:put {:handler (set-host-sshfps data-store)}
                               :get {:handler (get-host-sshfps data-store)}}]]]])
   (ring/create-default-handler)))
