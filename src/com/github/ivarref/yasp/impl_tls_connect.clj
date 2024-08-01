(ns com.github.ivarref.yasp.impl-tls-connect
  (:require [com.github.ivarref.yasp.tls-check :as tls-check])
  (:import (clojure.lang IAtom2)))

(defn tls-connect! [{:keys [state allow-connect? session socket-timeout-ms connect-timeout-ms now-ms tls-str] :as cfg} payload]
  (assert (instance? IAtom2 state))
  (assert (fn? allow-connect?) (str "Expected allow-connect? to be a fn. Was: " (pr-str allow-connect?)))
  (assert (string? session) "Expected :session to be a string")
  (assert (pos-int? socket-timeout-ms))
  (assert (pos-int? connect-timeout-ms))
  (assert (pos-int? now-ms))
  (assert (true? (tls-check/valid-tls-str? tls-str)))
  (assert (string? payload) "Expected :payload to be a string"))
