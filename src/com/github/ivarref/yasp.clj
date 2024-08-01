(ns com.github.ivarref.yasp
  (:require [clojure.tools.logging :as log]
            [com.github.ivarref.yasp.impl :as impl]
            [com.github.ivarref.yasp.impl-close :as impl-close]
            [com.github.ivarref.yasp.impl-connect :as impl-connect]
            [com.github.ivarref.yasp.impl-send :as impl-send]
            [com.github.ivarref.yasp.tls-check :as tls-check])
  (:import (clojure.lang IAtom2)))

(defonce default-state (atom {}))

(defn proxy!
  "Do the proxying!

  Move data to the requested remote host and port.

  This function should be invoked by the web server upon receiving a JSON HTTP POST request.

  The first argument, `cfg`, should be controlled by the server side.
  It contains details about which remote hosts can be accessed, whether
  to require mTLS, etc. See details below for what's possible.

  The second argument, `data`, should be the JSON request in the form of a map
  with the keys :op, :session, :payload, etc. This value should originate from
  the yasp client. The details are internal to yasp.

  Arguments
  =========

  `cfg` should be a map with the following keys:

  `:allow-connect?`: a function taking a map with the keys `:host` and `:port`.
  Return `nil` or `false` to block the connection request.
  Example function that allows all connections for port 22:
  (fn [{:keys [_host port]}]
    (= 22 port))
  This key is required.

  `:tls-str`: a String containing the concatenation of the root CA,
  the server CA and the server private key.
  If `nil` or an empty string is given, yasp will assume that
  the server is misconfigured.
  If this value is given, yasp will first send the received
  bytes to a mTLS server. The mTLS server's connection handler
  will then proxy the decoded bytes to the requested remote.
  The default value for `:tls-str` is `:yasp/none`, meaning that no mTLS termination
  will be performed.

  `:socket-timeout-ms`: Socket timeout for read operations in milliseconds.
  The client may override this setting when connecting.
   Default value is 100.

  `:connect-timeout-ms`: Connect timeout in milliseconds.
  The client may override this setting when connecting.
  Default value is 3000.

  `:chunk-size`: Size of buffer to use.
  The client may override this setting when connecting.
  Default value is 65536.
  "
  [{:keys [allow-connect? tls-str socket-timeout-ms connect-timeout-ms chunk-size]
    :or   {socket-timeout-ms  100
           connect-timeout-ms 3000
           chunk-size         65536
           tls-str            :yasp/none}
    :as   cfg}
   {:keys [op] :as data}]
  (assert (map? data) "Expected data to be a map")
  (assert (contains? data :op) "Expected data to contain :op key")
  (impl/assert-valid-op! op)
  (let [allow-connect? (impl/allow-connect-to-fn allow-connect?)
        state (get cfg :state default-state)
        now-ms (get cfg :now-ms (System/currentTimeMillis))
        shared-cfg {:state      state
                    :now-ms     now-ms
                    :chunk-size chunk-size}]
    (assert (instance? IAtom2 state))
    (assert (and (some? allow-connect?)
                 (fn? allow-connect?)) "Expected :allow-connect? to be a function")
    (assert (pos-int? now-ms))
    (assert (= tls-str :yasp/none))
    (cond (= "ping" op)
          {:res "pong"
           :tls "disabled"}

          (= "connect" op)
          (impl-connect/handle-connect! (assoc shared-cfg
                                          :allow-connect? allow-connect?
                                          :connect-timeout-ms connect-timeout-ms
                                          :socket-timeout-ms socket-timeout-ms
                                          :session (get cfg :session (str (random-uuid))))
                                        data)

          (= "send" op)
          (impl-send/handle-send! shared-cfg data)

          (= "close" op)
          (impl-close/handle-close! shared-cfg data))))

(defn tls-proxy!
  "Do the proxying!

  Same arguments as `proxy!`, but enforces that `:tls-str` is set."
  [{:keys [tls-str]
    :or   {tls-str :yasp/none}
    :as   cfg}
   data]
  (assert (map? data) "Expected data to be a map")
  (assert (contains? data :op) "Expected data to contain :op key")
  (let [op (get data :op)
        state (get cfg :state default-state)]
    (assert (instance? IAtom2 state))
    (impl/assert-valid-op! op)
    (cond (and (= op "ping") (true? (tls-check/valid-tls-str? tls-str)))
          {:res "pong"
           :tls "valid"}

          (and (= op "ping") (false? (tls-check/valid-tls-str? tls-str)))
          {:res "pong"
           :tls "invalid"}

          (false? (tls-check/valid-tls-str? tls-str))
          {:res "tls-config-error"})))

(defn close!
  ([]
   (close! default-state))
  ([state]
   (impl/close! state)))

