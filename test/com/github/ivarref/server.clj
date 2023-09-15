(ns com.github.ivarref.server
  (:require [com.github.ivarref.yasp :as yasp])
  (:import (clojure.lang IDeref)
           (java.io BufferedInputStream BufferedOutputStream Closeable)
           (java.lang AutoCloseable)
           (java.net InetAddress ServerSocket Socket SocketTimeoutException)))

(defonce server-state (atom {}))

(defn add-to-set! [state key what]
  (swap! state (fn [old-state] (update old-state key (fnil conj #{}) what))))

(defn remove-from-set [state key what]
  (swap! state (fn [old-state] (update old-state key (fnil disj #{}) what))))

(defn close-silently! [^Closeable sock state]
  (when (and sock (instance? Closeable sock))
    (try
      (.close sock)
      (catch Throwable t
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
        (println "Timeout waiting for future" fut))
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
        (println "error in accept-inner:" (ex-message t)))
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
        (println "error in handle:" (ex-message t))))))

(defn server-accept-loop [^ServerSocket ss state handler]
  (loop []
    (when-let [sock (accept-inner ss state)]
      (add-to-set! state :open-sockets sock)
      (let [fut (future (try
                          (swap! state update :active-futures (fnil inc 0))
                          (handler {:sock sock
                                    :state state
                                    :closed? (reify
                                               IDeref,
                                               (deref [_]
                                                 (closed? state)))})
                          (catch Throwable t
                            (when-not (closed? state)
                              (println "Exception in handler:" (class t) (ex-message t))))
                          (finally
                            (swap! state update :active-futures (fnil dec 0)))))]
        (add-to-set! state :futures fut))
      (recur))))

(defn start-server-impl!
  [{:keys [state]} handler]
  (let [ss (ServerSocket. 0 100 (InetAddress/getLoopbackAddress))
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
                  state
                  handler)
                (finally
                  (swap! state update :active-futures (fnil dec 0)))))]
    (add-to-set! state :open-sockets ss)
    (add-to-set! state :futures fut)
    ret))

(defn start-server!
  [{:keys [state] :as cfg} handler]
  (start-server-impl!
    (assoc cfg :state (or state server-state))
    handler))

