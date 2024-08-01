(ns com.github.ivarref.yasp.impl-tls-connect
  (:import (clojure.lang IAtom2)))

(defn handle-tls-connect! [{:keys [state allow-connect? session socket-timeout-ms connect-timeout-ms now-ms] :as cfg} payload]
  (assert (instance? IAtom2 state))
  (assert (fn? allow-connect?) (str "Expected allow-connect? to be a fn. Was: " (pr-str allow-connect?)))
  (assert (string? session) "Expected :session to be a string")
  (assert (pos-int? socket-timeout-ms))
  (assert (pos-int? connect-timeout-ms))
  (assert (pos-int? now-ms))
  (assert (string? payload) "Expected :payload to be a string"))
