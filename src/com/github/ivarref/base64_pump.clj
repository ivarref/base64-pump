(ns com.github.ivarref.base64-pump
  (:require [clojure.string :as str])
  (:import (java.io BufferedInputStream BufferedOutputStream Closeable)
           (java.net InetSocketAddress Socket)
           (java.util Base64)))

; connect
; send/recv
; close

(defn bytes->base64-str [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn parse-host-and-port [s]
  (let [[host port] (str/split s #":")]
    [host (Integer/valueOf port 10)]))

(defn handle-connect [{:keys [state allow-connect? connect-timeout session now-ms]
                       :or   {connect-timeout 3000
                              now-ms          (System/currentTimeMillis)
                              session         (str (random-uuid))}}
                      {:keys [payload]}]
  (assert (some? allow-connect?) "Expected :allow-connect? to be present")
  (assert (string? payload) "Expected :payload to be a string")
  (let [[host port :as host-and-port] (parse-host-and-port payload)]
    (if (allow-connect? host-and-port)
      (let [sock (Socket.)]
        (.setSoTimeout sock 200)
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
          (when (>= inactive-ms 60000)
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

(defn proxy-impl
  [{:keys [state] :as cfg} {:keys [op] :as data}]
  (assert (some? state))
  (assert (string? op) "Expected :op to be a string")
  (cond (= "connect" op)
        (handle-connect cfg data)

        (= "close" op)
        (handle-close cfg data)

        :else
        (throw (IllegalStateException. (str "Unexpected op: " (pr-str op))))))

(defonce default-state (atom {}))

(defn proxy!
  [{:keys [state now-ms] :as cfg} data]
  (assert (map? data) "Expected data to be a map")
  (proxy-impl
    (assoc cfg :state (or state default-state)
               :now-ms (or now-ms (System/currentTimeMillis)))
    data))


