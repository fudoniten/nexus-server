(ns nexus.sql-datastore
  (:refer-clojure :exclude [update set delete])
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from join where insert-into update values set delete-from columns]]
            [next.jdbc :as jdbc]
            [nexus.datastore :as datastore]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:import [java.io StringWriter PrintWriter]))

(defn pthru [o] (pprint o) o)

(defn- capture-stack-trace [e]
  (let [string-writer (StringWriter.)
        print-writer  (PrintWriter. string-writer)]
    (.printStackTrace e print-writer)
    (.flush print-writer)
    (.toString string-writer)))

(defn- exec! [store & sqls]
  (letfn [(log! [sql]
            (when (:verbose store)
              (println (str "executing: " sql)))
            sql)]
    (try
      (jdbc/with-transaction [tx (jdbc/get-connection (:datasource store))]
        (doseq [sql sqls]
          (jdbc/execute! tx (log! (sql/format sql)))))
      (catch Exception e
        (when (:verbose store)
          (println (capture-stack-trace e)))
        (throw e)))))

(defn- fetch! [store sql]
  (letfn [(log! [sql]
            (when (:verbose store)
              (println (str "fetching: " sql))
              sql))]
    (try
      (jdbc/execute! (:datasource store) (log! (sql/format sql)))
      (catch Exception e
        (when (:verbose store)
          (println (capture-stack-trace e)))
        (throw e)))))

(defn- host-has-record-sql [{:keys [domain host record-type]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :records.id)
        (from :records)
        (join :domains [:= :records.domain_id :domains.id])
        (where [:= :records.name fqdn]
               [:= :domains.name domain]
               [:= :records.type record-type]))))

(defn- domain-id-sql [domain]
  (-> (select :id)
      (from   :domains)
      (where  [:= :name domain])))

(defn- get-domain-id [store domain]
  (->> (domain-id-sql domain)
       (fetch! store)
       (pthru)
       :domains/id))

(defn- assoc-domain-id [store {:keys [domain] :as params}]
  (assoc params :domain-id (get-domain-id store domain)))

(defn- host-has-record? [store params]
  (->> (host-has-record-sql params)
       (fetch! store)
       (seq)))

(defn- host-has-ipv4? [store params]
  (host-has-record? store (assoc params :record-type "A")))

(defn- host-has-ipv6? [store params]
  (host-has-record? store (assoc params :record-type "AAAA")))

(defn- insert-records-sql [{:keys [host domain domain-id record-type contents]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (insert-into :records)
        (values (map (fn [content]
                       {:name      fqdn
                        :type      record-type
                        :content   content
                        :domain_id domain-id})
                     contents)))))

(defn- insert-host-ipv4-sql [params ip]
  (-> params
      (assoc :record-type "A")
      (assoc :contents [(str ip)])
      (insert-records-sql)))

(defn- insert-host-ipv6-sql [params ip]
  (-> params
      (assoc :record-type "AAAA")
      (assoc :contents [(str ip)])
      (insert-records-sql)))

(defn- insert-host-sshfps-sql [params sshfps]
  (-> params
      (assoc :record-type "SSHFP")
      (assoc :contents sshfps)
      (insert-records-sql)))

(defn- insert-host-ipv4 [store params ip]
  (exec! store
         (insert-host-ipv4-sql (pthru (assoc-domain-id store params)) ip)))

(defn- insert-host-ipv6 [store params ip]
    (exec! store
         (insert-host-ipv6-sql (assoc-domain-id store params) ip)))

(defn- update-record-sql [{:keys [domain host record-type content]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (update :records)
        (set {:content content})
        (where [:= :name      fqdn]
               [:= :type      record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- update-record-on-diff-sql [{:keys [content] :as params}]
  (-> (update-record-sql params)
      (where [:<> :content content])))

(defn- update-host-ipv4-sql [params ip]
  (-> params
      (assoc :record-type "A")
      (assoc :content (str ip))
      (update-record-on-diff-sql)))

(defn- update-host-ipv6-sql [params ip]
  (-> params
      (assoc :record-type "AAAA")
      (assoc :content (str ip))
      (update-record-on-diff-sql)))

(defn- update-host-ipv4 [store params ip]
  (exec! store (update-host-ipv4-sql params ip)))

(defn- update-host-ipv6 [store params ip]
  (exec! store (update-host-ipv6-sql params ip)))

(defn- delete-host-sshfps-sql [{:keys [host domain]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (delete-from :records)
        (where [:= :name      fqdn]
               [:= :type      "SSHFP"]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- set-host-ipv4-impl [store params ip]
  (if (host-has-ipv4? store params)
    (update-host-ipv4 store params ip)
    (insert-host-ipv4 store params ip))
  ip)

(defn- set-host-ipv6-impl [store params ip]
  (if (host-has-ipv6? store params)
    (update-host-ipv6 store params ip)
    (insert-host-ipv6 store params ip))
  ip)

(defn- set-host-sshpfs-impl [store params sshfps]
  (let [params-with-domid (assoc-domain-id store params)]
    (exec! store
           (delete-host-sshfps-sql params-with-domid)
           (insert-host-sshfps-sql params-with-domid sshfps)))
  sshfps)

(defn- get-record-contents-sql [{:keys [record-type domain host]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :content)
        (from :records)
        (where [:= :name fqdn]
               [:= :type record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- get-host-ipv4-sql [params]
  (get-record-contents-sql (assoc params :record-type "A")))

(defn- get-host-ipv6-sql [params]
  (get-record-contents-sql (assoc params :record-type "AAAA")))

(defn- get-host-sshfps-sql [params]
  (get-record-contents-sql (assoc params :record-type "SSHFP")))

(defn- get-host-ipv4-impl [store params]
  (->> (get-host-ipv4-sql params)
       (fetch! store)
       (pthru)
       :records/content))

(defn- get-host-ipv6-impl [store params]
  (->> (get-host-ipv6-sql params)
       (fetch! store)
       (pthru)
       :records/content))

(defn- get-host-sshfps-impl [store params]
  (pthru (fetch! store (get-host-sshfps-sql params))))

(defrecord SqlDataStore [verbose datasource]

  datastore/IDataStore

  (set-host-ipv4 [self domain host ip]
    (set-host-ipv4-impl self
                        {:domain domain :host host}
                        ip))
  (set-host-ipv6 [self domain host ip]
    (set-host-ipv6-impl self
                        {:domain domain :host host}
                        ip))
  (set-host-sshfps [self domain host sshfps]
    (set-host-sshpfs-impl self
                          {:domain domain :host host}
                          sshfps))

  (get-host-ipv4 [self domain host]
    (get-host-ipv4-impl self {:domain domain :host host}))
  (get-host-ipv6 [self domain host]
    (get-host-ipv6-impl self {:domain domain :host host}))
  (get-host-sshfps [self domain host]
    (get-host-sshfps-impl self {:domain domain :host host})))

(defn connect [{:keys [database-user database-password-file database-host database-port database verbose]
                :or {database-port 5432
                     verbose       false}}]
  (SqlDataStore. verbose
                 (jdbc/get-datasource {:dbtype   "postgresql"
                                       :dbname   database
                                       :user     database-user
                                       :password (-> database-password-file
                                                     (slurp)
                                                     (str/trim))
                                       :host     database-host
                                       :port     database-port})))
