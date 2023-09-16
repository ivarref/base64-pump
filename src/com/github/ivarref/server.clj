(ns com.github.ivarref.server
  (:require [com.github.ivarref.yasp.impl :as impl])
  (:refer-clojure :exclude [println])
  (:import (clojure.lang IDeref)
           (java.io BufferedInputStream BufferedOutputStream Closeable)
           (java.lang AutoCloseable)
           (java.net InetAddress ServerSocket Socket SocketTimeoutException)))

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
        (impl/atomic-println "Timeout waiting for future" fut))
      (remove-from-set state :futures fut)))
  (swap! state dissoc :closed?))

(defn closed? [state]
  (when state
    (true? (get @state :closed?))))

(defn accept-inner [^ServerSocket ss state]
  (try
    (.accept ss)
    (catch Throwable t
      (when-not (closed? state)
        (impl/atomic-println "error in accept-inner:" (ex-message t)))
      nil)))

(defn echo-handler [{:keys [^Socket sock closed?]}]
  (try
    (with-open [in (BufferedInputStream. (.getInputStream sock))
                out (BufferedOutputStream. (.getOutputStream sock))]
      (loop [c 0]
        (when-let [r (try
                       (.read in)
                       (catch SocketTimeoutException ste
                         nil))]
          (when (not= r -1)
            (.write out ^Integer r)
            (.flush out)
            (recur (inc c))))))
    (catch Throwable t
      (when-not @closed?
        (impl/atomic-println "error in handle:" (ex-message t))))))

(defn server-accept-loop [^ServerSocket ss {:keys [so-timeout state]} handler]
  (loop []
    (when-let [^Socket sock (accept-inner ss state)]
      (.setSoTimeout sock (or so-timeout 100))
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
                            (when-not (closed? state)
                              (impl/atomic-println "Exception in handler:" (class t) (ex-message t))))
                          (finally
                            (swap! state update :active-futures (fnil dec 0)))))]
        (add-to-set! state :futures fut))
      (recur))))

(defn start-server-impl!
  [{:keys [state port] :as cfg} handler]
  (let [ss (ServerSocket. (if port
                            port
                            0) 100 (InetAddress/getLoopbackAddress))
        ret (reify
              AutoCloseable
              (close [_]
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
                  (swap! state update :active-futures (fnil dec 0)))))]
    (add-to-set! state :open-sockets ss)
    (add-to-set! state :futures fut)
    ret))

(defonce server-state (atom {}))

(defn start-server!
  "
  cfg: Configuration map.

  Arguments:

  `handler` should be a function that takes a single map with the keys:
  * `sock` of type java.net.Socket.
  * `closed?` of type clojure.lang.IDeref. This is `true` if the server
  is shutting down. Can be used to suppress warnings/errors occurring on shutdown."
  (^AutoCloseable [cfg handler]
   (start-server! server-state cfg handler))
  (^AutoCloseable [state cfg handler]
   (start-server-impl!
     (assoc cfg :state state)
     handler)))

