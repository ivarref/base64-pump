(ns com.github.ivarref.server
  (:refer-clojure :exclude [println])
  (:require [clojure.tools.logging :as log]
            [com.github.ivarref.yasp.utils :as u]
            [com.github.ivarref.yasp.tls :as tls])
  (:import (clojure.lang IDeref)
           (java.io BufferedInputStream BufferedOutputStream Closeable InputStream OutputStream)
           (java.lang AutoCloseable)
           (java.net InetAddress InetSocketAddress ServerSocket Socket)
           (javax.net.ssl SSLServerSocket)))

(defn add-to-set! [state key what]
  (swap! state (fn [old-state] (update old-state key (fnil conj #{}) what))))

(defn remove-from-set [state key what]
  (swap! state (fn [old-state] (update old-state key (fnil disj #{}) what))))

(defn close-silently! [^Closeable sock state]
  (when (and sock (instance? Closeable sock))
    (try
      (.close sock)
      (catch Throwable _t
        nil)
      (finally
        (remove-from-set state :open-sockets sock)))))

(defn close! [state]
  (swap! state assoc :closed? true)
  (doseq [sock (get @state :open-sockets)]
    (close-silently! sock state))
  (doseq [fut (get @state :futures)]
    (if (= :timeout (deref fut 1000 :timeout))
      (do
        (log/warn "Timeout waiting for future" fut))
      (remove-from-set state :futures fut)))
  (swap! state dissoc :closed?))

(defn closed? [state]
  (when state
    (true? (get @state :closed?))))

(defn accept-inner [^ServerSocket ss state]
  (try
    (if (instance? SSLServerSocket ss)
      (tls/accept ss)
      (.accept ss))
    (catch Throwable t
      (if-not (closed? state)
        (log/warn "Error in accept-inner on port" (.getLocalPort ss) ":" (ex-message t))
        (log/debug "Error in accept-inner on port" (.getLocalPort ss) ":" (ex-message t)))
      nil)))

(defn echo-handler [{:keys [^Socket sock closed?]}]
  (try
    (with-open [in (BufferedInputStream. (.getInputStream sock))
                out (BufferedOutputStream. (.getOutputStream sock))]
      (loop []
        (if-let [chunk (u/read-max-bytes in 1024)]
          (do
            (u/write-bytes chunk out)
            (recur))
          (do
            (log/debug "Echo handler: EOF reached")))))
    (catch Throwable t
      (if-not @closed?
        (log/error t "Echo handler error:" (ex-message t))
        (log/debug t "Echo handler error:" (ex-message t))))
    (finally
      (log/debug "Echo handler exiting"))))

(defn server-accept-loop [^ServerSocket ss {:keys [socket-timeout state] :as _cfg} handler]
  (loop []
    (when-let [^Socket sock (accept-inner ss state)]
      (assert (pos-int? socket-timeout) ":socket-timeout must be a pos-int")
      (.setSoTimeout sock socket-timeout)
      (add-to-set! state :open-sockets sock)
      (let [fut (future (try
                          (swap! state update :active-futures (fnil inc 0))
                          (try
                            (handler {:sock    sock
                                      :state   state
                                      :closed? (reify
                                                 IDeref,
                                                 (deref [_]
                                                   (closed? state)))})
                            (finally
                              (close-silently! sock state)))
                          (catch Throwable t
                            (if-not (closed? state)
                              (log/error "Unexpected exception in handler:" (class t) (ex-message t))
                              (log/debug t "Exception in handler:" (class t) (ex-message t))))
                          (finally
                            (swap! state update :active-futures (fnil dec 0)))))]
        (add-to-set! state :futures fut))
      (recur))))

(defn start-server-impl!
  [{:keys [state tls-context tls-port local-port] :as cfg} handler]
  (let [ss (if tls-context
             (tls/server-socket tls-context "127.0.0.1" (or tls-port 0))
             (doto
               (ServerSocket. (if local-port
                                local-port
                                0) 10 (InetAddress/getLoopbackAddress))
               (.setReuseAddress true)))
        ret (reify
              AutoCloseable
              (close [_]
                (log/info "Closing proxy running at port" (.getLocalPort ss))
                (close! state))
              IDeref
              (deref [_]
                (.getLocalPort ss)))
        fut (future
              (try
                (swap! state update :active-futures (fnil inc 0))
                (server-accept-loop
                  ss
                  cfg
                  handler)
                (finally
                  (log/debug "Accept loop exiting for port" (.getLocalPort ss))
                  (swap! state update :active-futures (fnil dec 0)))))]
    (add-to-set! state :open-sockets ss)
    (add-to-set! state :futures fut)
    ret))

(defonce server-state (atom {}))

(defn start-server!
  "
  cfg: Configuration map with the keys:
  * `socket-timeout`: Socket timeout for read operations in milliseconds. Default: 100 ms.

  Arguments:

  `handler` should be a function that takes a single map with the keys:
  * `sock` of type java.net.Socket.
  * `closed?` of type clojure.lang.IDeref. This is `true` if the server
  is shutting down. Can be used to suppress warnings/errors occurring on shutdown."
  (^AutoCloseable [cfg handler]
   (start-server! server-state cfg handler))
  (^AutoCloseable [state cfg handler]
   (start-server-impl!
     (assoc (merge {:socket-timeout 100}
                   cfg)
       :state state)
     handler)))

(defonce id (atom 0))

(defn pump [^InputStream is ^OutputStream os]
  (if-let [chunk (u/read-max-bytes is 64000)]
    (do
      (u/write-bytes chunk os)
      true)
    false))

(defn pump-socks [^Socket src ^Socket dst]
  (with-open [in (BufferedInputStream. (.getInputStream src))
              out (BufferedOutputStream. (.getOutputStream dst))]
    (loop []
      (when (pump in out)
        (recur)))))

(defn bootstrap-tls-proxy! [{:keys [tls-str connect-timeout socket-timeout] :as cfg} {:keys [host port]}]
  (let [my-id (swap! id inc)
        state (atom {})
        tls-proxy (start-server!
                    state
                    (assoc cfg :tls-context (tls/ssl-context-or-throw tls-str nil))
                    (fn [{:keys [^Socket sock closed?]}]
                      (try
                        (let [remote (Socket.)]
                          (.setSoTimeout remote socket-timeout)
                          (when (try
                                  (log/info "TLS proxy" my-id "received connection")
                                  (.connect remote (InetSocketAddress. ^String host ^Integer port) ^Integer connect-timeout)
                                  (add-to-set! state :open-sockets remote)
                                  true
                                  (catch Throwable t
                                    (log/warn t "TLS proxy" my-id "could not connect to remote host" host port)
                                    nil))
                            (log/info "TLS proxy" my-id "OK connected to" host port)
                            (let [fut (future
                                        (try
                                          (swap! state update :active-futures (fnil inc 0))
                                          (try
                                            (pump-socks sock remote)
                                            (catch Throwable t
                                              (if (or (.isClosed sock) (.isClosed remote))
                                                (log/info "TLS pump2: Src or dst closed connection. Got exception:" (ex-message t))
                                                (log/error t "Unexpected error in TLS pump2:" (ex-message t)))))
                                          (catch Throwable t
                                            (log/error "Unexpected exception in TLS pump2:" (ex-message t)))
                                          (finally
                                            (log/info "TLS pump2 exiting")
                                            (swap! state update :active-futures (fnil dec 0)))))]
                              (add-to-set! state :futures fut)
                              (try
                                (pump-socks remote sock)
                                (catch Throwable t
                                  (if (or (.isClosed sock) (.isClosed remote))
                                    (log/info "TLS pump1: Src or dst closed connection. Got exception:" (ex-message t))
                                    (log/error t "Unexpected error in TLS pump1:" (ex-message t)))))
                              @fut)))
                        (finally
                          (log/info "TLS pump1 exiting")))))]
    (log/info "TLS proxy" my-id "running on port" @tls-proxy ", forwarding to" host port)
    {:state    state
     :proxy    tls-proxy
     :running? true}))
