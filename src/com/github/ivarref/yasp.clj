(ns com.github.ivarref.yasp
  (:require [com.github.ivarref.yasp.impl :as impl]))

(defonce default-state (atom {}))

(defn proxy!
  "Do the proxying!

  Move data to the requested remote host and port.

  This function should be invoked by the web server upon receiving a JSON HTTP POST request.

  The first argument, `cfg`, should be controlled by the server side.

  The second argument, `data`, should be the JSON request in the form of a map
  with the keys :op, :session, :payload, etc.


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
  The client may override this setting when connecting.
   Default value is 100.

  `:connect-timeout`: Connect timeout in milliseconds.
  The client may override this setting when connecting.
  Default value is 3000.

  `:chunk-size`: Size of buffer to use.
  The client may override this setting when connecting.
  Default value is 65536.
  "
  [{:keys [allow-connect? socket-timeout connect-timeout chunk-size tls-str]
    :or   {socket-timeout  100
           connect-timeout 3000
           chunk-size      65536
           tls-str         :yasp/none}
    :as   cfg}
   data]
  (assert (map? data) "Expected data to be a map")
  (assert (contains? data :op) "Expected data to contain :op key")
  (assert (some? allow-connect?) "Expected :allow-connect? to be present")
  (impl/proxy-impl
    (assoc cfg
      :state (get cfg :state default-state)
      :now-ms (get cfg :now-ms (System/currentTimeMillis))
      :session (get cfg :session (str (random-uuid)))
      :chunk-size (get cfg :chunk-size chunk-size)
      :socket-timeout socket-timeout
      :connect-timeout connect-timeout
      :tls-str tls-str)
    data))

(defn close!
  ([]
   (close! default-state))
  ([state]
   (impl/close! state)))
