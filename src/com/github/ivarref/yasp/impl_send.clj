(ns com.github.ivarref.yasp.impl-send
  (:require [clojure.tools.logging :as log]
            [com.github.ivarref.yasp.opts :as opts]
            [com.github.ivarref.yasp.utils :as u]
            [com.github.ivarref.yasp.impl-close :as impl-close])
  (:import (clojure.lang IAtom2)
           (java.io BufferedOutputStream InputStream)))

(defn- receive-bytes [cfg
                      {:keys [^InputStream in] :as _session-state}
                      {:keys [session]}]
  (assert (string? session) "Expected :session to be a string")
  (assert (instance? InputStream in))
  (assert (pos-int? (get cfg opts/chunk-size-bytes)))
  (if-let [read-bytes (u/read-max-bytes in (get cfg opts/chunk-size-bytes))]
    (do
      (if (pos-int? (count read-bytes))
        (log/debug "Proxy: Received" (count read-bytes) "bytes from remote")
        (log/trace "Proxy: Received" (count read-bytes) "bytes from remote"))
      {:res     "ok-send"
       :payload (u/bytes->base64-str read-bytes)})
    (do
      (impl-close/handle-close! cfg {:session session})
      {:res "eof"})))

(defn handle-send!
  [{:keys [state now-ms] :as cfg} {:keys [session payload hasmore] :as opts}]
  (assert (instance? IAtom2 state))
  (assert (pos-int? now-ms))
  (assert (string? session) "Expected :session to be a string")
  (assert (string? payload) "Expected :payload to be a string")
  (assert (string? hasmore) "Expected :hasmore to be a string")
  (assert (pos-int? (get cfg opts/chunk-size-bytes)))
  (assert (contains? #{"true" "false"} hasmore))
  (if-let [sess (get-in @state [:sessions session])]
    (let [{:keys [^BufferedOutputStream out]} sess
          bytes-to-send (u/base64-str->bytes payload)]
      (u/write-bytes bytes-to-send out)
      (when (or (= "false" hasmore) (= 0 (count bytes-to-send)))
        (.flush out))
      (if (pos-int? (count bytes-to-send))
        (log/debug "Proxy: Wrote" (count bytes-to-send) "bytes to remote")
        (log/trace "Proxy: Wrote" (count bytes-to-send) "bytes to remote"))
      (swap! state assoc-in [:sessions session :last-access] now-ms)
      (receive-bytes cfg sess opts))
    {:res "unknown-session"}))
