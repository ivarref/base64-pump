(ns com.github.ivarref.yasp-test
  (:require
    [clj-commons.pretty.repl]
    [clojure.java.io :as io]
    [clojure.test :as t]
    [com.github.ivarref.server :as s]
    [com.github.ivarref.yasp :as yasp]
    [com.github.ivarref.yasp.impl :as impl])
  (:import (java.io BufferedOutputStream ByteArrayInputStream)
           (java.net Socket)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(t/deftest disallow-connect
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (t/is (= {:res "disallow-connect"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (impl/pr-str-safe {:host "localhost" :port @ss})}))))))

(t/deftest expire-connections
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{{:host "localhost" :port @ss}}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (impl/pr-str-safe {:host "localhost" :port @ss})}))))
    (t/is (map? (get @st "1")))
    (impl/expire-connections! st 50001)
    (t/is (map? (get @st "1")))
    (impl/expire-connections! st 600000)
    (t/is (= ::none (get @st "1" ::none)))))

(t/deftest close-connection
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{{:host "localhost" :port @ss}}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (impl/pr-str-safe {:host "localhost" :port @ss})})))
      (t/is (map? (get @st "1")))
      (t/is (= {:res "ok-close"} (yasp/proxy! {:state          st
                                               :allow-connect? #{{:host "localhost" :port @ss}}
                                               :now-ms         1}
                                              {:op      "close"
                                               :session "1"})))
      (t/is (= {:res "unknown-session"} (yasp/proxy! {:state          st
                                                      :allow-connect? #{{:host "localhost" :port @ss}}}
                                                     {:op      "close"
                                                      :session "1"})))
      (t/is (= ::none (get @st "1" ::none))))))

(def hello-world-bytes (.getBytes "Hello World" StandardCharsets/UTF_8))

(def hello-world-base64 (impl/bytes->base64-str hello-world-bytes))

(defn send-and-read! [cfg data read-times]
  (let [received (atom {})
        add-recv! (fn [resp]
                    (swap! received #(update %1 %2 (fnil inc 0)) resp))]
    (add-recv! (yasp/proxy! cfg data))
    (dotimes [_n read-times]
      (add-recv! (yasp/proxy! cfg (assoc data :payload ""))))
    @received))

(t/deftest send-test
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (let [cfg {:state          st
                 :allow-connect? #{{:host "localhost" :port @ss}}
                 :now-ms         0
                 :session        "1"}]
        (t/is (= {:res     "ok-connect"
                  :session "1"}
                 (yasp/proxy! cfg {:op      "connect"
                                   :payload (impl/pr-str-safe {:host "localhost" :port @ss})})))
        (t/is (map? (get @st "1")))

        (t/is (= {{:res "ok-send", :payload ""}                 10
                  {:res "ok-send", :payload "SGVsbG8gV29ybGQ="} 1}
                 (send-and-read! cfg
                                 {:op      "send"
                                  :session "1"
                                  :payload hello-world-base64}
                                 10)))))))

(defn say-hello [{:keys [^Socket sock closed?]}]
  (try
    (with-open [in (ByteArrayInputStream. hello-world-bytes)
                out (BufferedOutputStream. (.getOutputStream sock))]
      (io/copy in out)
      (.flush out))
    (catch Throwable t
      (when-not @closed?
        (impl/atomic-println "error in say-hello:" (ex-message t))))))

(t/deftest send-eof-test
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} say-hello)]
      (let [cfg {:state          st
                 :allow-connect? #{{:host "localhost" :port @ss}}
                 :session        "1"}]
        (t/is (= {:res     "ok-connect"
                  :session "1"}
                 (yasp/proxy! cfg
                              {:op      "connect"
                               :payload (impl/pr-str-safe {:host "localhost" :port @ss})})))
        (let [recv (send-and-read!
                     cfg
                     {:op      "send"
                      :session "1"
                      :payload ""}
                     10)]
          (t/is (= 1 (get recv {:res "eof"})))
          (t/is (= 1 (get recv {:res "ok-send", :payload "SGVsbG8gV29ybGQ="}))))

        (t/is (= {} @st))))))
