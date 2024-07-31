(ns com.github.ivarref.yasp.impl-connect
  (:require [clojure.edn :as edn]
            [clojure.stacktrace :as st]
            [clojure.tools.logging :as log]
            [com.github.ivarref.yasp.utils :as u])
  (:import (clojure.lang IAtom2)
           (java.io BufferedInputStream BufferedOutputStream)
           (java.net ConnectException InetSocketAddress Socket SocketTimeoutException UnknownHostException)))

; Private API, subject to change
(comment (set! *warn-on-reflection* true))
(declare handle-connect-impl!)

(defn handle-connect! [{:keys [state allow-connect? session socket-timeout-ms connect-timeout-ms now-ms] :as cfg}
                       {:keys [payload]}]
  (assert (instance? IAtom2 state))
  (assert (fn? allow-connect?) (str "Expected allow-connect? to be a fn. Was: " (pr-str allow-connect?)))
  (assert (string? session) "Expected :session to be a string")
  (assert (pos-int? socket-timeout-ms))
  (assert (pos-int? connect-timeout-ms))
  (assert (pos-int? now-ms))
  (assert (string? payload) "Expected :payload to be a string")
  (let [{:keys [host port]} (edn/read-string payload)]
    (assert (string? host) "Expected :host in client-config to be a string")
    (assert (pos-int? port) "Expected :port in client-config to be a pos-int")
    (if (allow-connect? {:host host :port port})
      (handle-connect-impl! cfg host port)
      (do
        (log/warn "Returning disallow-connect for" (str host ":" port))
        {:res "disallow-connect"}))))

(defn- handle-connect-impl! [{:keys [state session socket-timeout-ms connect-timeout-ms now-ms]}
                             host port]
  (let [sock (Socket.)]
    (.setSoTimeout sock socket-timeout-ms)
    (try
      (.connect sock (InetSocketAddress. ^String host ^Integer port) ^Integer connect-timeout-ms)
      (let [in (BufferedInputStream. (.getInputStream sock))
            out (BufferedOutputStream. (.getOutputStream sock))
            session-state {:socket      sock
                           :in          in
                           :out         out
                           :last-access now-ms}]
        (swap! state assoc-in [:sessions session] session-state)
        (log/info "Accepted connection for" (str host ":" port))
        {:res     "ok-connect"
         :session session})
      (catch UnknownHostException uhe
        (log/warn "Unknown host exception during connect:" (ex-message uhe))
        {:res     "connect-error"
         :payload (str "unknown host: " host
                       "\nremote-host: " (u/str-quote host)
                       "\nremote-port: " (u/str-quote port))})
      (catch SocketTimeoutException ste
        (log/warn "Socket timeout during connect:" (ex-message ste))
        {:res     "connect-error"
         :payload (str "SocketTimeoutException: " (ex-message ste)
                       "\nremote-host: " (u/str-quote host)
                       "\nremote-port: " (u/str-quote port))})
      (catch ConnectException ce
        (log/warn "Connect exception during connect:" (ex-message ce))
        {:res     "connect-error"
         :payload (str "ConnectException: " (ex-message ce) ", root message: " (ex-message (st/root-cause ce))
                       "\nremote-host: " (u/str-quote host)
                       "\nremote-port: " (u/str-quote port))})
      (catch Throwable t
        (log/error t "Unhandled exception in connect:" (ex-message t))
        (log/error "Error message:" (ex-message t) "of type" (str (class t)))
        {:res     "connect-error"
         :payload (str (ex-message t)
                       " of type "
                       (str (class t))
                       "\nremote-host: " (u/str-quote host)
                       "\nremote-port: " (u/str-quote port))}))))
