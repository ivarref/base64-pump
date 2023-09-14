(ns com.github.ivarref.base64-pump-test
  (:require
    [clojure.test :as t]
    [clj-commons.pretty.repl]
    [com.github.ivarref.base64-pump :as bp])
  (:import (java.net ServerSocket)))

(clj-commons.pretty.repl/install-pretty-exceptions)

(def ^:dynamic *echo-port* nil)

(defn handle [sock]
  (println "dum di dam..."))

(defn accept-inner [^ServerSocket ss]
  (try
    (.accept ss)
    (catch Throwable t
      nil)))

(defn accept [^ServerSocket ss]
  (loop []
    (when-let [sock (accept-inner ss)]
      (future (handle sock))
      (recur))))

(defn with-echo-server [f]
  (with-open [ss (ServerSocket. 0)]
    (binding [*echo-port* (.getLocalPort ss)]
      (let [fut (future (accept ss))]
        (f)
        (bp/close-silently! ss)
        (when (= :timeout (deref fut 500 :timeout))
          (println "Timeout waiting for accept server"))))))

(t/use-fixtures :each with-echo-server)

(t/deftest disallow-connect
  (let [st (atom {})]
    (t/is (= {:res "disallow-connect"}
             (bp/proxy! {:state          st
                         :allow-connect? #{}
                         :now-ms         0
                         :session        "1"}
                        {:op      "connect"
                         :payload (str "localhost:" *echo-port*)})))))

(t/deftest expire-connections
  (let [st (atom {})]
    (t/is (= {:res "ok-connect"
              :session "1"}
             (bp/proxy! {:state          st
                         :allow-connect? #{["localhost" *echo-port*]}
                         :now-ms         0
                         :session        "1"}
                        {:op      "connect"
                         :payload (str "localhost:" *echo-port*)})))
    (t/is (map? (get @st "1")))
    (bp/expire-connections! st 50001)
    (t/is (map? (get @st "1")))
    (bp/expire-connections! st 60000)
    (t/is (= ::none (get @st "1" ::none)))))

(t/deftest close-connection
  (let [st (atom {})]
    (t/is (= {:res "ok-connect"
              :session "1"}
             (bp/proxy! {:state          st
                         :allow-connect? #{["localhost" *echo-port*]}
                         :now-ms         0
                         :session        "1"}
                        {:op      "connect"
                         :payload (str "localhost:" *echo-port*)})))
    (t/is (map? (get @st "1")))
    (t/is (= {:res "ok-close"} (bp/proxy! {:state st}
                                          {:op      "close"
                                           :session "1"})))
    (t/is (= {:res "unknown-session"} (bp/proxy! {:state st}
                                                 {:op      "close"
                                                  :session "1"})))
    (t/is (= ::none (get @st "1" ::none)))))
