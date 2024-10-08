(ns nexus.datastore)

(defprotocol IDataStore
  (set-host-ipv4   [_ domain host ipv4])
  (set-host-ipv6   [_ domain host ipv6])
  (set-host-sshfps [_ domain host sshfps])
  (get-host-ipv4   [_ domain host])
  (get-host-ipv6   [_ domain host])
  (get-host-sshfps [_ domain host])
  (get-challenge-records   [_ domain])
  (create-challenge-record [_ domain host challenge-id secret])
  (delete-challenge-record [_ domain challenge-id]))
