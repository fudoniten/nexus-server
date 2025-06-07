(ns nexus.sql-datastore-test
  (:require [clojure.test :refer :all]
            [nexus.sql-datastore :as sql]
            [next.jdbc :as jdbc]))

(def test-db {:dbtype "postgresql"
              :dbname "nexus_test"
              :user "nexus"
              :password "secret"
              :host "localhost"
              :port 5432})

(defn with-test-db [test-fn]
  (jdbc/with-transaction [tx (jdbc/get-datasource test-db)]
    (jdbc/execute! tx ["CREATE TABLE IF NOT EXISTS domains (id SERIAL PRIMARY KEY, name TEXT UNIQUE NOT NULL)"])
    (jdbc/execute! tx ["CREATE TABLE IF NOT EXISTS records (id SERIAL PRIMARY KEY, domain_id INTEGER REFERENCES domains(id), name TEXT NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL)"])
    (jdbc/execute! tx ["CREATE TABLE IF NOT EXISTS challenges (id SERIAL PRIMARY KEY, domain_id INTEGER REFERENCES domains(id), challenge_id UUID NOT NULL, hostname TEXT NOT NULL, record_id INTEGER REFERENCES records(id), active BOOLEAN DEFAULT true)"])
    (test-fn)
    (jdbc/execute! tx ["DROP TABLE challenges"])
    (jdbc/execute! tx ["DROP TABLE records"])  
    (jdbc/execute! tx ["DROP TABLE domains"])))

(use-fixtures :each with-test-db)

(deftest test-set-and-get-host-ipv4
  (let [store (sql/connect test-db)]
    (is (nil? (sql/get-host-ipv4 store "example.com" "www")))
    (sql/set-host-ipv4 store "example.com" "www" "1.2.3.4")
    (is (= "1.2.3.4" (sql/get-host-ipv4 store "example.com" "www")))))

(deftest test-set-and-get-host-ipv6  
  (let [store (sql/connect test-db)]
    (is (nil? (sql/get-host-ipv6 store "example.com" "www")))
    (sql/set-host-ipv6 store "example.com" "www" "::1")
    (is (= "::1" (sql/get-host-ipv6 store "example.com" "www")))))

(deftest test-set-and-get-host-sshfps
  (let [store (sql/connect test-db)
        sshfps ["1 1 123456" "1 2 789012"]]
    (is (nil? (sql/get-host-sshfps store "example.com" "www")))  
    (sql/set-host-sshfps store "example.com" "www" sshfps)
    (is (= sshfps (sql/get-host-sshfps store "example.com" "www")))))

(deftest test-create-and-delete-challenge-record  
  (let [store (sql/connect test-db)
        challenge-id (java.util.UUID/randomUUID)]
    (is (empty? (sql/get-challenge-records store "example.com")))
    (sql/create-challenge-record store "example.com" "_acme-challenge" challenge-id "secret")
    (is (= [challenge-id] (sql/get-challenge-records store "example.com")))
    (sql/delete-challenge-record store "example.com" challenge-id)  
    (is (empty? (sql/get-challenge-records store "example.com")))))
