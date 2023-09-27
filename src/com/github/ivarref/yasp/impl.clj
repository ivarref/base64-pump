(ns com.github.ivarref.yasp.impl
  (:refer-clojure :exclude [future])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.stacktrace :as st]
            [clojure.tools.logging :as log]
            [com.github.ivarref.server :as server]
            [com.github.ivarref.yasp.tls :as tls]
            [com.github.ivarref.yasp.utils :as u])
  (:import (java.io BufferedInputStream BufferedOutputStream InputStream)
           (java.net ConnectException InetSocketAddress Socket SocketTimeoutException UnknownHostException)))

; Private API, subject to change

(comment
  (set! *warn-on-reflection* true))

(defn handle-connect [{:keys [state allow-connect? connect-timeout session now-ms socket-timeout chunk-size tls-str]
                       :as   cfg}
                      {:keys [payload]}]
  (assert (some? allow-connect?) "Expected :allow-connect? to be present")
  (assert (string? payload) "Expected :payload to be a string")
  (let [{:keys [host port] :as client-config} (edn/read-string payload)]
    (if (allow-connect? (select-keys client-config [:host :port]))
      (do
        (when (not= :yasp/none tls-str)
          (locking state
            (if (false? (get-in @state [:tls-proxy host port :running?] false))
              (let [new-proxy (server/bootstrap-tls-proxy! cfg client-config)]
                (swap! state assoc-in [:tls-proxy host port] new-proxy))
              (log/debug "TLS proxy bootstrapped, doing nothing"))))
        (let [sock (Socket.)
              socket-timeout (get client-config :socket-timeout socket-timeout)
              connect-timeout (get client-config :connect-timeout connect-timeout)
              chunk-size (get client-config :chunk-size chunk-size)
              old-port port
              port (if (not= :yasp/none tls-str)
                     (deref (get-in @state [:tls-proxy host port :proxy]))
                     port)
              host (if (some? tls-str) "127.0.0.1" host)]
          (when (not= old-port port)
            (log/debug "Overriding port from" old-port "to" port))
          (.setSoTimeout sock socket-timeout)
          (try
            (.connect sock (InetSocketAddress. ^String host ^Integer port) ^Integer connect-timeout)
            (let [in (BufferedInputStream. (.getInputStream sock))
                  out (BufferedOutputStream. (.getOutputStream sock))]
              (swap! state (fn [old-state]
                             (assoc-in old-state [:sessions session] {:socket      sock
                                                                      :in          in
                                                                      :out         out
                                                                      :last-access now-ms
                                                                      :chunk-size  chunk-size})))
              (log/info "Accepted connection for"
                        (into (sorted-map) (select-keys client-config [:host :port]))
                        "mTLS"
                        (if (not= :yasp/none tls-str)
                          "enabled"
                          "disabled"))
              {:res     "ok-connect"
               :session session})
            (catch UnknownHostException uhe
              (log/warn "Unknown host exception during connect:" (ex-message uhe))
              {:res     "connect-error"
               :payload (str "unknown host: " host)})
            (catch SocketTimeoutException ste
              (log/warn "Socket timeout during connect:" (ex-message ste))
              {:res     "connect-error"
               :payload (str "SocketTimeoutException: " (ex-message ste))})
            (catch ConnectException ce
              (log/warn "Connect exception during connect:" (ex-message ce))
              {:res     "connect-error"
               :payload (str "ConnectException: " (ex-message ce))})
            (catch Throwable t
              (log/error t "Unhandled exception in connect:" (ex-message t))
              (log/error "Error message:" (ex-message t) "of type" (str (class t)))
              {:res     "connect-error"
               :payload (str (ex-message t)
                             " of type "
                             (str (class t)))}))))
      (do
        (log/warn "Returning disallow-connect for" (into (sorted-map) (select-keys client-config [:host :port])))
        {:res "disallow-connect"}))))

(defn close-session! [state session-id]
  (if-let [sess (get-in @state [:sessions session-id])]
    (do (u/close-silently! (get sess :in))
        (u/close-silently! (get sess :out))
        (u/close-silently! (get sess :socket))
        (swap! state (fn [old-state] (update old-state :sessions dissoc session-id)))
        {:res "ok-close"})
    {:res "unknown-session"}))

(defn expire-connections! [state now-ms]
  (when state
    (let [s @state]
      (doseq [[session-id {:keys [last-access]}] (get s :sessions)]
        (let [inactive-ms (- now-ms last-access)]
          (when (or (= now-ms -1) (>= inactive-ms (* 10 60000)))
            (close-session! state session-id)))))))

(defn close! [state]
  (when state
    (expire-connections! state -1)
    (let [s @state]
      (doseq [[host v] (get s :tls-proxy)]
        (doseq [[port tls-proxy] v]
          (log/debug "Shutting down TLS proxy forwarding to" host port)
          (server/close! (get tls-proxy :state))
          (swap! state (fn [old-state] (assoc-in old-state [:tls-proxy host port]
                                                 {:running?    false
                                                  :final-state @(get tls-proxy :state)}))))))))

(defn handle-close
  [{:keys [state]} {:keys [session]}]
  (assert (string? session) "Expected :session to be a string")
  (close-session! state session))

(defn handle-recv [{:keys [state] :as cfg} {:keys [session] :as data}]
  (assert (string? session) "Expected :session to be a string")
  (if-let [sess (get-in @state [:sessions session])]
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
  (if-let [sess (get-in @state [:sessions session])]
    (let [{:keys [^BufferedOutputStream out]} sess
          bytes-to-send (u/base64-str->bytes payload)]
      (u/write-bytes bytes-to-send out)
      (if (pos-int? (count bytes-to-send))
        (log/debug "Proxy: Wrote" (count bytes-to-send) "bytes to remote")
        (log/trace "Proxy: Wrote" (count bytes-to-send) "bytes to remote"))
      (swap! state assoc-in [:sessions session :last-access] now-ms)
      (merge
        {:res "ok-send"}
        (handle-recv cfg opts)))
    {:res "unknown-session"}))

(defn proxy-impl
  [{:keys [state tls-str tls-file] :as cfg} {:keys [op session] :as data}]
  (try
    (assert (some? state))
    (assert (string? op) "Expected :op to be a string")
    (expire-connections! state (:now-ms cfg))
    (let [[tls-file-err tls-str] (if (not= :yasp/none tls-file)
                                   (if (and (string? tls-file) (.exists (io/file tls-file)))
                                     [false (slurp tls-file)]
                                     (do
                                       (log/error "Missing tls-file" tls-file "or unhandled type")
                                       [true (str "missing-tls-file")]))
                                   [false tls-str])
          cfg (assoc cfg :tls-str tls-str)]
      (if-let [tls-error (if tls-file-err
                           {:res     "tls-config-error"
                            :payload tls-str}
                           (when (not= tls-str :yasp/none)
                             (locking state
                               (when (false? (get @state :tls-verified? false))
                                 (try
                                   (tls/ssl-context-or-throw tls-str nil)
                                   (swap! state assoc :tls-verified? true)
                                   (log/info "TLS server context verified")
                                   nil
                                   (catch Throwable t
                                     (log/error "TLS configuration error:" (ex-message t))
                                     {:res     "tls-config-error"
                                      :payload (str "Message: " (ex-message t))}))))))]
        tls-error
        (cond (= "connect" op)
              (handle-connect cfg data)

              (= "close" op)
              (handle-close cfg data)

              (= "send" op)
              (handle-send cfg data)

              (= "ping" op)
              {:res "pong"}

              :else
              (throw (IllegalStateException. (str "Unexpected op: " (pr-str op)))))))
    (catch Throwable t
      (log/error "Unexpected error:" (ex-message t))
      (log/error "Root cause:" (ex-message (st/root-cause t)))
      {:res     "error"
       :payload (str "Message: " (ex-message t)
                     " of type " (str (class t))
                     ". Root cause:" (ex-message (st/root-cause t)))})))
