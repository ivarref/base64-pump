(ns com.github.ivarref.yasp.impl
  (:refer-clojure :exclude [future println])                ; no threads used :-)
  (:require [clojure.edn :as edn]
            [clojure.stacktrace :as st]
            [clojure.tools.logging :as log]
            [com.github.ivarref.yasp.utils :as u])
  (:import (java.io BufferedInputStream BufferedOutputStream InputStream)
           (java.net InetSocketAddress Socket)))

; Private API, subject to change

(comment
  (set! *warn-on-reflection* true))

(defn handle-connect [{:keys [state allow-connect? connect-timeout session now-ms socket-timeout chunk-size]}
                      {:keys [payload]}]
  (assert (some? allow-connect?) "Expected :allow-connect? to be present")
  (assert (string? payload) "Expected :payload to be a string")
  (let [{:keys [host port] :as client-config} (edn/read-string payload)]
    (if (allow-connect? (select-keys client-config [:host :port]))
      (let [sock (Socket.)
            socket-timeout (get client-config :socket-timeout socket-timeout)
            connect-timeout (get client-config :connect-timeout connect-timeout)
            chunk-size (get client-config :chunk-size chunk-size)]
        (.setSoTimeout sock socket-timeout)
        (.connect sock (InetSocketAddress. ^String host ^Integer port) ^Integer connect-timeout)
        (let [in (BufferedInputStream. (.getInputStream sock))
              out (BufferedOutputStream. (.getOutputStream sock))]
          (swap! state (fn [old-state]
                         (assoc old-state session
                                          {:socket      sock
                                           :in          in
                                           :out         out
                                           :last-access now-ms
                                           :chunk-size  chunk-size})))
          {:res     "ok-connect"
           :session session}))
      {:res "disallow-connect"})))

(defn expire-connections! [state now-ms]
  (when state
    (let [s @state]
      (doseq [[session-id {:keys [socket in out last-access]}] s]
        (let [inactive-ms (- now-ms last-access)]
          (when (>= inactive-ms (* 10 60000))
            (u/close-silently! in)
            (u/close-silently! out)
            (u/close-silently! socket)
            (swap! state dissoc session-id)
            #_(println "closing" session-id)))))))

(defn handle-close
  [{:keys [state]} {:keys [session]}]
  (assert (string? session) "Expected :session to be a string")
  (if-let [sess (get @state session)]
    (do (u/close-silently! (get sess :in))
        (u/close-silently! (get sess :out))
        (u/close-silently! (get sess :socket))
        (swap! state dissoc session)
        {:res "ok-close"})
    {:res "unknown-session"}))

(defn handle-recv [{:keys [state] :as cfg} {:keys [session] :as data}]
  (assert (string? session) "Expected :session to be a string")
  (if-let [sess (get @state session)]
    (let [{:keys [^InputStream in chunk-size]} sess]
      (if-let [read-bytes (u/read-max-bytes in chunk-size)]
        (do
          (if (pos-int? (count read-bytes))
            (log/debug "Proxy: Received" (count read-bytes) "bytes from remote")
            (log/trace "Proxy: Received" (count read-bytes) "bytes from remote"))
          {:payload (u/bytes->base64-str read-bytes)})
        (do
          (handle-close cfg data)
          {:res "eof"})))
    {:res "unknown-session"}))

(defn handle-send
  [{:keys [state now-ms] :as cfg} {:keys [session payload] :as opts}]
  (assert (string? session) "Expected :session to be a string")
  (assert (string? payload) "Expected :payload to be a string")
  (if-let [sess (get @state session)]
    (let [{:keys [^BufferedOutputStream out]} sess
          bytes-to-send (u/base64-str->bytes payload)]
      (u/copy-bytes bytes-to-send out)
      (if (pos-int? (count bytes-to-send))
        (log/debug "Proxy: Wrote" (count bytes-to-send) "bytes to remote")
        (log/trace "Proxy: Wrote" (count bytes-to-send) "bytes to remote"))
      (swap! state assoc-in [session :last-access] now-ms)
      (merge
        {:res "ok-send"}
        (handle-recv cfg opts)))
    {:res "unknown-session"}))

(defn proxy-impl
  [{:keys [state] :as cfg} {:keys [op] :as data}]
  (try
    (assert (some? state))
    (assert (string? op) "Expected :op to be a string")
    (expire-connections! state (:now-ms cfg))
    (cond (= "connect" op)
          (handle-connect cfg data)

          (= "close" op)
          (handle-close cfg data)

          (= "send" op)
          (handle-send cfg data)

          (= "ping" op)
          {:res "pong"}

          :else
          (throw (IllegalStateException. (str "Unexpected op: " (pr-str op)))))
    (catch Throwable t
      (log/error t "Unexpected error:" (ex-message t))
      (log/error "Root cause:" (ex-message (st/root-cause t)))
      {:res "error"
       :payload (str "Message: " (ex-message t)
                     ". Root cause:" (ex-message (st/root-cause t)))})))
