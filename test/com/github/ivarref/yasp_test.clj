(ns com.github.ivarref.yasp-test
  (:require
    [clj-commons.pretty.repl]
    [clojure.java.io :as io]
    [clojure.test :as t]
    [com.github.ivarref.server :as s]
    [com.github.ivarref.yasp :as yasp]
    [com.github.ivarref.yasp.impl :as impl]
    [com.github.ivarref.yasp.opts :as opts]
    [com.github.ivarref.yasp.utils :as u])
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
                             :now-ms         1
                             :session        "1"}
                            {:op      "connect"
                             :payload (u/pr-str-safe {:host "localhost" :port @ss})}))))))

(t/deftest expire-connections
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{{:host "localhost" :port @ss}}
                             :now-ms         1
                             :session        "1"}
                            {:op      "connect"
                             :payload (u/pr-str-safe {:host "localhost" :port @ss})}))))
    (t/is (map? (get-in @st [:sessions "1"])))
    (impl/expire-connections! st 50001)
    (t/is (map? (get-in @st [:sessions "1"])))
    (impl/expire-connections! st 600000)
    (t/is (= ::none (get @st "1" ::none)))))

(t/deftest close-connection
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{{:host "localhost" :port @ss}}
                             :now-ms         1
                             :session        "1"}
                            {:op      "connect"
                             :payload (u/pr-str-safe {:host "localhost" :port @ss})})))
      (t/is (map? (get-in @st [:sessions "1"])))
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

(def hello-world-base64 (u/bytes->base64-str hello-world-bytes))

(t/deftest send-test
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (let [cfg {:state             st
                 :allow-connect?    #{{:host "localhost" :port @ss}}
                 :now-ms            1
                 :session           "1"
                 :socket-timeout-ms 3000}]
        (t/is (= {:res     "ok-connect"
                  :session "1"}
                 (yasp/proxy! cfg {:op      "connect"
                                   :payload (u/pr-str-safe {:host "localhost" :port @ss})})))
        (t/is (map? (get-in @st [:sessions "1"])))
        (let [^Socket sock (get-in @st [:sessions "1" :socket])]
          (assert (instance? Socket sock))
          (t/is (= 3000 (.getSoTimeout sock))))
        (t/is (= {:res "ok-send", :payload "SGVsbG8gV29ybGQ="}
                 (yasp/proxy! cfg
                              {:op      "send"
                               :session "1"
                               :hasmore "false"             ; make sure the underlying stream is flushed
                               :payload hello-world-base64})))))))

(t/deftest no-reply-test
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} s/consume-only-handler)]
      (let [cfg {:state             st
                 :allow-connect?    #{{:host "localhost" :port @ss}}
                 :now-ms            1
                 :session           "1"
                 :socket-timeout-ms 500}]
        (t/is (= {:res     "ok-connect"
                  :session "1"}
                 (yasp/proxy! cfg {:op      "connect"
                                   :payload (u/pr-str-safe {:host "localhost" :port @ss})})))
        (t/is (map? (get-in @st [:sessions "1"])))

        (t/is (= {:res "ok-send", :payload ""}
                 (yasp/proxy! cfg
                              {:op      "send"
                               :session "1"
                               :hasmore "false"
                               :payload hello-world-base64})))))))

(defn say-hello [{:keys [^Socket sock closed?]}]
  (try
    (with-open [in (ByteArrayInputStream. hello-world-bytes)
                out (BufferedOutputStream. (.getOutputStream sock))]
      (io/copy in out)
      (.flush out))
    (catch Throwable t
      (when-not @closed?
        (u/atomic-println "error in say-hello:" (ex-message t))))))

(t/deftest send-eof-test
  (let [st (atom {})]
    (with-open [ss (s/start-server! (atom {}) {} say-hello)]
      (let [cfg {:state                 st
                 :allow-connect?        (impl/allow-connect-to-fn #{{:host "localhost" :port @ss}})
                 :session               "1"
                 opts/socket-timeout-ms 3000}]
        (t/is (= {:res     "ok-connect"
                  :session "1"}
                 (yasp/proxy! cfg
                              {:op      "connect"
                               :payload (u/pr-str-safe {:host "localhost" :port @ss})})))
        (t/is (= {:res "ok-send", :payload "SGVsbG8gV29ybGQ="}
                 (yasp/proxy! cfg {:op "send" :session "1" :payload "" :hasmore "false"})))
        (t/is (= {:res "eof"}
                 (yasp/proxy! cfg {:op "send" :session "1" :payload "" :hasmore "false"})))
        (t/is (= {} (get @st :sessions)))))))

(t/deftest unknown-host-test
  (let [cfg {:state                  (atom {})
             :allow-connect?         (constantly true)
             :session                "1"
             opts/connect-timeout-ms 100}]
    (t/is (= "connect-error"
             (:res (yasp/proxy! cfg
                                {:op      "connect"
                                 :payload (u/pr-str-safe {:host "unknown.example.com" :port 12345})}))))))

(t/deftest connect-timeout-test
  (let [cfg {:state                  (atom {})
             :allow-connect?         (constantly true)
             :session                "1"
             opts/connect-timeout-ms 100}]
    (t/is (= "connect-error"
             (:res (yasp/proxy! cfg
                                {:op      "connect"
                                 :payload (u/pr-str-safe {:host "example.com" :port 12345})}))))))
