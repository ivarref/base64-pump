(ns com.github.ivarref.yasp.impl-tls-connect
  (:require [com.github.ivarref.yasp.tls-check :as tls-check]
            [com.github.ivarref.yasp.opts :as opts])
  (:import (clojure.lang IAtom2)))

; Private API, subject to change
(comment (set! *warn-on-reflection* true))

(defn tls-connect! [{:keys [state allow-connect? session now-ms tls-str] :as cfg}
                    {:keys [payload]}]
  (assert (instance? IAtom2 state))
  (assert (fn? allow-connect?) (str "Expected allow-connect? to be a fn. Was: " (pr-str allow-connect?)))
  (assert (string? session) "Expected :session to be a string")
  (assert (pos-int? (get cfg opts/socket-timeout-ms)))
  (assert (pos-int? (get cfg opts/connect-timeout-ms)))
  (assert (pos-int? now-ms))
  (assert (true? (tls-check/valid-tls-str? tls-str)))
  (assert (string? payload) (str "Expected :payload to be a string. Was: " (type payload))))
