(ns com.github.ivarref.yasp
  (:require [com.github.ivarref.yasp.impl :as impl])
  (:refer-clojure :exclude [future println]))               ; no threads used :-)

(defonce default-state (atom {}))

(defn proxy!
  "Do the proxying!

  Move bytes from `data` to the requested remote host and port.

  Arguments
  =========

  `cfg` should be a map with the following keys:

  `:allow-connect?`: a function taking a map with the keys `:host` and `:port`.
  Return `nil` or `false` to block the connection request.
  Example function that allows all connections for port 22:
  (fn [{:keys [_host port]}]
    (= 22))
  This key is required.

  `:socket-timeout`: Socket timeout for read operations in milliseconds.
   Default value is 100.

  `:connect-timeout`: Connect timeout in milliseconds.
  Default value is 3000.
  "
  [{:keys [allow-connect? socket-timeout connect-timeout] :as cfg} data]
  (assert (map? data) "Expected data to be a map")
  (assert (contains? data :op) "Expected data contain :op key")
  (assert (some? allow-connect?) "Expected :allow-connect? to be present")
  (impl/proxy-impl
    (-> cfg
        (assoc :state (or (:state cfg) default-state)
               :now-ms (or (:now-ms cfg) (System/currentTimeMillis))
               :socket-timeout (or socket-timeout 100)
               :connect-timeout (or connect-timeout 3000)))
    data))
