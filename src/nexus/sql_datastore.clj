(ns nexus.sql-datastore
  (:refer-clojure :exclude [update set delete])
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from join where insert-into update values set delete-from returning]]
            [next.jdbc :as jdbc]
            [slingshot.slingshot :refer [throw+]]
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
  (some->> (domain-id-sql domain)
           (fetch! store)
           (first)
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
  (some->> (get-host-ipv4-sql params)
           (fetch! store)
           (first)
           :records/content))

(defn- get-host-ipv6-impl [store params]
  (some->> (get-host-ipv6-sql params)
           (fetch! store)
           (first)
           :records/content))

(defn- get-host-sshfps-impl [store params]
  (some->> (get-host-sshfps-sql params)
           (fetch! store)
           (map :records/content)))

(defn- create-challenge-record-sql [{:keys [host domain domain-id secret]}]
  (-> (insert-into :records)
      (values [{:name      (format "%s.%s" host domain)
                :type      "TXT"
                :content   secret
                :domain_id domain-id}])
      (returning :id)))

(defn- create-challenge-log-record-sql [{:keys [host domain-id challenge-id record-id]}]
  (let [challenge-uuid (parse-uuid challenge-id)]
    (-> (insert-into :challenges)
        (values [{:domain_id    domain-id
                  :challenge_id challenge-uuid
                  :hostname     host
                  :record_id    record-id}]))))

;; Need to implement 'exec!' manually, since one query depends on the prev
(defn- create-challenge-record-impl [store params]
  (let [log! (fn [sql]
               (when (:verbose store)
                 (println (str "executing: " sql)))
               sql)
        params-with-domid (assoc-domain-id store params)]
    (try (jdbc/with-transaction [tx (jdbc/get-connection (:datasource store))]
           (let [create-challenge-record (log! (sql/format (create-challenge-record-sql params-with-domid)))
                 record-id (some-> (jdbc/execute! tx create-challenge-record)
                                   (first)
                                   :records/id)]
             (if record-id
               (jdbc/execute! tx
                              (log! (sql/format (create-challenge-log-record-sql (assoc params-with-domid
                                                                                        :record-id record-id)))))
               (throw+ {::type ::insert-challenge-failed
                        ::msg  "failed to insert new challenge record"}))))
      (catch Exception e
        (when (:verbose store)
          (println (capture-stack-trace e)))
        (throw e)))))

(defn- get-challenge-record-ids-sql [{:keys [domain-id]}]
  (-> (select :challenge_id)
      (from :challenges)
      (where [:= :domain_id domain-id]
             [:= :active true])))

-(defn- get-challenge-records-impl [store params]
  (let [params-with-domid (assoc-domain-id store params)]
    (some->> (get-challenge-record-ids-sql params-with-domid)
             (fetch! store)
             (map :challenges/challenge_id))))

(defn- get-challenge-record-id-sql [{:keys [domain-id challenge-id]}]
  (-> (select :record_id)
      (from :challenges)
      (where [:= :domain_id domain-id]
             [:= :challenge_id challenge-id])))

(defn- get-challenge-record-id [store params]
  (some->> (get-challenge-record-id-sql params)
           (fetch! store)
           (first)
           :challenges/record_id))

(defn- delete-record-by-id-sql [{:keys [record-id]}]
  (-> (delete-from :records)
      (where [:= :record_id record-id])))

(defn- delete-challenge-record-log-sql [{:keys [domain-id challenge-id]}]
  (-> (update :challenges)
      (set {:active false})
      (where [:= :domain_id    domain-id]
             [:= :challenge_id challenge-id])))

(defn- delete-challenge-record-impl [store params]
  (let [params-with-domid (assoc-domain-id store params)
        record-id (get-challenge-record-id store params-with-domid)]
    (if record-id
      (do (exec! store
                 (delete-record-by-id-sql (assoc params-with-domid :record-id record-id))
                 (delete-challenge-record-log-sql params-with-domid))
          true)
      false)))

(defrecord SqlDataStore [verbose datasource]

  datastore/IDataStore

  (set-host-ipv4 [self domain host ip]
    (when verbose
      (println (format "setting ipv4 for %s.%s: %s"
                       host domain ip)))
    (set-host-ipv4-impl self
                        {:domain domain :host host}
                        ip))
  (set-host-ipv6 [self domain host ip]
    (when verbose
      (println (format "setting ipv6 for %s.%s: %s"
                       host domain ip)))
    (set-host-ipv6-impl self
                        {:domain domain :host host}
                        ip))
  (set-host-sshfps [self domain host sshfps]
    (when verbose
      (println (format "setting sshfps for %s.%s: %s"
                       host domain sshfps)))
    (set-host-sshpfs-impl self
                          {:domain domain :host host}
                          sshfps))

  (get-host-ipv4 [self domain host]
    (get-host-ipv4-impl self {:domain domain :host host}))
  (get-host-ipv6 [self domain host]
    (get-host-ipv6-impl self {:domain domain :host host}))
  (get-host-sshfps [self domain host]
    (get-host-sshfps-impl self {:domain domain :host host}))

  (get-challenge-records [self domain]
    (when verbose
      (println (format "fetching challenge records for domain %s" domain)))
    (get-challenge-records-impl self {:domain domain}))
  (create-challenge-record [self domain host challenge-id secret]
    (when verbose
      (println (format "creating challenge record %s for domain %s" challenge-id domain)))
    (create-challenge-record-impl self {:domain       domain
                                        :host         host
                                        :secret       secret
                                        :challenge-id challenge-id}))
  (delete-challenge-record [self domain challenge-id]
    (when verbose
      (println (format "removing challenge record %s for domain %s" challenge-id domain)))
    (delete-challenge-record-impl self {:domain domain :challenge-id challenge-id})))

(defn connect [{:keys [database-user database-password-file database-host database-port database verbose]
                :or {database-port 5432
                     verbose       false}}]
  (when verbose (println (str "initializing sql datastore: " database-host ":" database-port "/" database)))
  (SqlDataStore. verbose
                 (jdbc/get-datasource {:dbtype   "postgresql"
                                       :dbname   database
                                       :user     database-user
                                       :password (-> database-password-file
                                                     (slurp)
                                                     (str/trim))
                                       :host     database-host
                                       :port     database-port})))
