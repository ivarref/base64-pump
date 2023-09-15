(ns com.github.ivarref.yasp-test
  (:require
    [clj-commons.pretty.repl]
    [clojure.test :as t]
    [com.github.ivarref.yasp :as yasp])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.net ServerSocket Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)))

(clj-commons.pretty.repl/install-pretty-exceptions)

(def ^:dynamic *echo-port* nil)

(defn echo-handler [^Socket sock]
  (try
    (with-open [in (BufferedInputStream. (.getInputStream sock))
                out (BufferedOutputStream. (.getOutputStream sock))]
      (loop [c 0]
        (when-let [r (try
                       (.read in)
                       (catch SocketTimeoutException ste
                         nil))]
          (when (not= r -1)
            (.write out r)
            (.flush out)
            (recur (inc c))))))
    (catch Throwable t
      (println "error in handle:" (ex-message t)))))

(defn accept-inner [^ServerSocket ss]
  (try
    (.accept ss)
    (catch Throwable t
      nil)))

(defn accept [^ServerSocket ss]
  (loop []
    (when-let [sock (accept-inner ss)]
      (future (echo-handler sock))
      (recur))))

(defn with-echo-server [f]
  (with-open [ss (ServerSocket. 0)]
    (binding [*echo-port* (.getLocalPort ss)]
      (let [fut (future (accept ss))]
        (f)
        (yasp/close-silently! ss)
        (when (= :timeout (deref fut 500 :timeout))
          (println "Timeout waiting for accept server"))))))

(t/use-fixtures :each with-echo-server)

(t/deftest disallow-connect
  (let [st (atom {})]
    (t/is (= {:res "disallow-connect"}
             (yasp/proxy! {:state        st
                           :allow-connect? #{}
                           :now-ms         0
                           :session        "1"}
                          {:op      "connect"
                           :payload (str "localhost:" *echo-port*)})))))

(t/deftest expire-connections
  (let [st (atom {})]
    (t/is (= {:res "ok-connect"
              :session "1"}
             (yasp/proxy! {:state        st
                           :allow-connect? #{["localhost" *echo-port*]}
                           :now-ms         0
                           :session        "1"}
                          {:op      "connect"
                           :payload (str "localhost:" *echo-port*)})))
    (t/is (map? (get @st "1")))
    (yasp/expire-connections! st 50001)
    (t/is (map? (get @st "1")))
    (yasp/expire-connections! st 60000)
    (t/is (= ::none (get @st "1" ::none)))))

(t/deftest close-connection
  (let [st (atom {})]
    (t/is (= {:res "ok-connect"
              :session "1"}
             (yasp/proxy! {:state        st
                           :allow-connect? #{["localhost" *echo-port*]}
                           :now-ms         0
                           :session        "1"}
                          {:op      "connect"
                           :payload (str "localhost:" *echo-port*)})))
    (t/is (map? (get @st "1")))
    (t/is (= {:res "ok-close"} (yasp/proxy! {:state st}
                                            {:op      "close"
                                             :session "1"})))
    (t/is (= {:res "unknown-session"} (yasp/proxy! {:state st}
                                                   {:op      "close"
                                                    :session "1"})))
    (t/is (= ::none (get @st "1" ::none)))))

(t/deftest send-test
  (let [st (atom {})]
    (t/is (= {:res "ok-connect"
              :session "1"}
             (yasp/proxy! {:state        st
                           :allow-connect? #{["localhost" *echo-port*]}
                           :now-ms         0
                           :session        "1"}
                          {:op      "connect"
                           :payload (str "localhost:" *echo-port*)})))
    (t/is (map? (get @st "1")))
    (let [data (yasp/bytes->base64-str (.getBytes "Hello World" StandardCharsets/UTF_8))]
      (t/is (= {:res "ok-send"}
               (yasp/proxy! {:state st}
                            {:op "send"
                             :session "1"
                             :payload data})))
      (t/is (= {:res "ok-recv"
                :payload data}
               (yasp/proxy! {:state st}
                            {:op "recv" :session "1"}))))

    #_(t/is (= {:res "unknown-session"} (yasp/proxy! {:state st}
                                                     {:op      "close"
                                                      :session "1"})))
    #_(t/is (= ::none (get @st "1" ::none)))))
