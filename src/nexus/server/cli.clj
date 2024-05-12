(ns nexus.server.cli
  (:require [nexus.server :as server]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [nexus.sql-datastore :as sql-store]
            [nexus.authenticator :as auth]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.core.async :refer [chan >!! go-loop]]
            [clojure.set :as set])
  (:gen-class))

(def cli-opts
  [["-k" "--host-keys HOST_KEYS"
    "File containing host/key pairs, in json format."]

   ["-D" "--database DATABASE"
    "Database name of the PowerDNS database."]

   ["-U" "--database-user DB_USER"
    "User as which to connect to the PowerDNS database."]

   ["-W" "--database-password-file DB_PASSWORD_FILE"
    "File containing password with which to connect to the PowerDNS database."]

   ["-H" "--database-host DB_HOSTNAME"
    "Hostname of the Postgresql PowerDNS database."]

   ["-P" "--database-port DB_PORT"
    "Port of the Postgresql server backing PowerDNS."
    :parse-fn #(Integer/parseInt %)
    :default 5432]

   ["-h" "--listen-host HOST"
    "Hostname or IP address on which to listen."]

   ["-p" "--listen-port PORT"
    "Port on which to listen for incoming requests."
    :parse-fn #(Integer/parseInt %)]

   ["-v" "--verbose" "Verbose output."
    :default false]])

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: nexus [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- msg-quit [status msg]
  (println msg)
  (System/exit status))

(defn- parse-opts [args required cli-opts]
  (let [{:keys [options]
         :as result}     (cli/parse-opts args cli-opts)
        missing          (set/difference required (-> options keys set))
        missing-errors   (map #(format "missing required parameter: %s" %)
                              missing)]
    (update result :errors concat missing-errors)))

(defn serve! [app {:keys [host port]}]
  (run-jetty app { :host host :port port :join? false }))

(defn -main [& args]
  (let [required-keys #{:host-keys
                        :database
                        :database-user
                        :database-password-file
                        :database-host
                        :database-port
                        :listen-host
                        :listen-port
                        :verbose}
        {:keys [options _ errors summary]}
        (parse-opts args required-keys cli-opts)]
    (println (str "keys: " (str/join ", " (map name (keys options)))))
    (when (seq errors)    (msg-quit 1 (usage summary errors)))
    (when (:help options) (msg-quit 0 (usage summary)))
    (when (:verbose options)
      (println "Options:")
      (println (str/join \newline (map (fn [[k v]] (str "  " (name k) ": " v)) options))))
    (let [authenticator  (auth/read-key-collection (:host-keys options))
          store          (sql-store/connect options)
          app            (server/create-app :authenticator authenticator
                                            :data-store    store
                                            :verbose       (:verbose options))
          catch-shutdown (chan)
          server         (serve! #'app {:port (:listen-port options)
                                        :host (:listen-host options)})]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! catch-shutdown true))))
      (<!! catch-shutdown)
      (.stop server)
      (System/exit 0))))
