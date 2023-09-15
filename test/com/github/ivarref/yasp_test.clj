(ns com.github.ivarref.yasp-test
  (:require
    [clj-commons.pretty.repl]
    [clojure.test :as t]
    [com.github.ivarref.yasp :as yasp]
    [com.github.ivarref.server :as s])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.net ServerSocket Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)))

(clj-commons.pretty.repl/install-pretty-exceptions)

(t/deftest disallow-connect
  (let [st (atom {})]
    (with-open [ss (s/start-server! {:state (atom {})} s/echo-handler)]
      (t/is (= {:res "disallow-connect"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (str "localhost:" @ss)}))))))

(t/deftest expire-connections
  (let [st (atom {})]
    (with-open [ss (s/start-server! {:state (atom {})} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{["localhost" @ss]}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (str "localhost:" @ss)}))))
    (t/is (map? (get @st "1")))
    (yasp/expire-connections! st 50001)
    (t/is (map? (get @st "1")))
    (yasp/expire-connections! st 60000)
    (t/is (= ::none (get @st "1" ::none)))))

(t/deftest close-connection
  (let [st (atom {})]
    (with-open [ss (s/start-server! {:state (atom {})} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{["localhost" @ss]}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (str "localhost:" @ss)})))
      (t/is (map? (get @st "1")))
      (t/is (= {:res "ok-close"} (yasp/proxy! {:state st}
                                              {:op      "close"
                                               :session "1"})))
      (t/is (= {:res "unknown-session"} (yasp/proxy! {:state st}
                                                     {:op      "close"
                                                      :session "1"})))
      (t/is (= ::none (get @st "1" ::none))))))

(t/deftest send-test
  (let [st (atom {})]
    (with-open [ss (s/start-server! {:state (atom {})} s/echo-handler)]
      (t/is (= {:res     "ok-connect"
                :session "1"}
               (yasp/proxy! {:state          st
                             :allow-connect? #{["localhost" @ss]}
                             :now-ms         0
                             :session        "1"}
                            {:op      "connect"
                             :payload (str "localhost:" @ss)})))
      (t/is (map? (get @st "1")))
      (let [data (yasp/bytes->base64-str (.getBytes "Hello World" StandardCharsets/UTF_8))]
        (t/is (= {:res     "ok-send"
                  :payload data}
                 (yasp/proxy! {:state  st
                               :now-ms 1}
                              {:op      "send"
                               :session "1"
                               :payload data})))
        (t/is (= 1 (get-in @st ["1" :last-access])))

        ; empty send is OK
        (t/is (= {:res     "ok-send"
                  :payload ""}
                 (yasp/proxy! {:state  st
                               :now-ms 2}
                              {:op      "send"
                               :session "1"
                               :payload ""})))

        (t/is (= 2 (get-in @st ["1" :last-access])))))))
