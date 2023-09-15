(ns com.github.ivarref.yasp.impl
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:refer-clojure :exclude [future])                        ; no threads used :-)
  (:import (java.io BufferedInputStream BufferedOutputStream ByteArrayInputStream ByteArrayOutputStream Closeable InputStream)
           (java.net InetSocketAddress Socket SocketTimeoutException)
           (java.util Base64)))

; Private API, subject to change

(comment
  (set! *warn-on-reflection* true))
; connect
; send/recv
; close

(defn bytes->base64-str [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn base64-str->bytes [^String base64-str]
  (.decode (Base64/getDecoder) base64-str))

(defn parse-host-and-port [s]
  (let [[host port] (str/split s #":")]
    [host (Integer/valueOf port 10)]))

(defn handle-connect [{:keys [state allow-connect? connect-timeout session now-ms socket-timeout]
                       :or   {connect-timeout 3000
                              socket-timeout  100
                              now-ms          (System/currentTimeMillis)
                              session         (str (random-uuid))}}
                      {:keys [payload]}]
  (assert (some? allow-connect?) "Expected :allow-connect? to be present")
  (assert (string? payload) "Expected :payload to be a string")
  (let [[host port :as host-and-port] (parse-host-and-port payload)]
    (if (allow-connect? host-and-port)
      (let [sock (Socket.)]
        (.setSoTimeout sock socket-timeout)
        (.connect sock (InetSocketAddress. ^String host ^Integer port) ^Integer connect-timeout)
        (let [in (BufferedInputStream. (.getInputStream sock))
              out (BufferedOutputStream. (.getOutputStream sock))]
          (swap! state (fn [old-state]
                         (assoc old-state session
                                          {:socket      sock
                                           :in          in
                                           :out         out
                                           :last-access now-ms})))
          {:res     "ok-connect"
           :session session}))
      {:res "disallow-connect"})))

(defn close-silently! [^Closeable c]
  (when (and c (instance? Closeable c))
    (try
      (.close c)
      (catch Throwable t
        nil))))

(defn expire-connections! [state now-ms]
  (when state
    (let [s @state]
      (doseq [[session-id {:keys [socket in out last-access]}] s]
        (let [inactive-ms (- now-ms last-access)]
          (when (>= inactive-ms (* 10 60000))
            (close-silently! in)
            (close-silently! out)
            (close-silently! socket)
            (swap! state dissoc session-id)
            #_(println "closing" session-id)))))))

(defn close! [state]
  (when state
    (let [s @state]
      (doseq [[session-id {:keys [socket in out]}] s]
        (println "closing" session-id)))))

(comment
  (defonce s (atom {}))
  (handle-connect {:state          s
                   :session-id     "123"
                   :allow-connect? (constantly true)
                   :now-ms         123 #_#{["localhost" 22]}}
                  {:payload "example.com:443"}))

(defn handle-close
  [{:keys [state]} {:keys [session]}]
  (assert (string? session) "Expected :session to be a string")
  (if-let [sess (get @state session)]
    (do (close-silently! (get sess :in))
        (close-silently! (get sess :out))
        (close-silently! (get sess :socket))
        (swap! state dissoc session)
        {:res "ok-close"})
    {:res "unknown-session"}))

(defn read-max-bytes [^InputStream in max-bytes]
  (let [out (ByteArrayOutputStream.)
        eof? (atom false)]
    (loop [c 0]
      (when-let [r (try
                     (.read in)
                     (catch SocketTimeoutException ste
                       nil))]
        (if (= r -1)
          (reset! eof? true)
          (do
            (.write out ^Integer r)
            (when (not= max-bytes c)
              (recur (inc c)))))))
    (let [byte-array (.toByteArray out)]
      (if (and @eof?
               (= 0 (count byte-array)))
        nil
        byte-array))))

(comment
  (String. (read-max-bytes
             (ByteArrayInputStream. (.getBytes "Hello World"))
             1024)))

(defn handle-recv [{:keys [state] :as cfg} {:keys [session] :as data}]
  (assert (string? session) "Expected :session to be a string")
  (if-let [sess (get @state session)]
    (let [{:keys [^InputStream in]} sess]
      (if-let [read-bytes (read-max-bytes in 1024)]
        {:payload (bytes->base64-str read-bytes)}
        (do
          (handle-close cfg data)
          {:res "eof"})))
    {:res "unknown-session"}))

(defn handle-send
  [{:keys [state now-ms] :as cfg} {:keys [session payload] :as opts}]
  (assert (string? session) "Expected :session to be a string")
  (assert (string? payload) "Expected :payload to be a string")
  (if-let [sess (get @state session)]
    (let [{:keys [^BufferedOutputStream out ^Socket socket]} sess]
      (if (not (.isClosed socket))
        (let [bytes-to-send (base64-str->bytes payload)]
          (with-open [bais (ByteArrayInputStream. bytes-to-send)]
            (io/copy bais out))
          (.flush out)
          (swap! state assoc-in [session :last-access] now-ms)
          (merge
            {:res "ok-send"}
            (handle-recv cfg opts)))
        {:res "socket-closed"})
      #_{:res "ok-close"})
    {:res "unknown-session"}))

(defn proxy-impl
  [{:keys [state] :as cfg} {:keys [op] :as data}]
  (assert (some? state))
  (assert (string? op) "Expected :op to be a string")
  (expire-connections! state (:now-ms cfg))
  (cond (= "connect" op)
        (handle-connect cfg data)

        (= "close" op)
        (handle-close cfg data)

        (= "send" op)
        (handle-send cfg data)

        :else
        (throw (IllegalStateException. (str "Unexpected op: " (pr-str op))))))
