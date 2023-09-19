(ns com.github.ivarref.tls-test
  (:require [clojure.test :as t]
            [com.github.ivarref.locksmith :as locksmith]
            [com.github.ivarref.server :as s]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp.tls :as tls]
            [com.github.ivarref.yasp.utils :as u]))

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (locksmith/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

(t/deftest tls-hello
  (let [st (atom {})
        [server-keys client-keys] (gen-key-pair)
        tls-context (tls/ssl-context-or-throw server-keys nil)]
    (with-open [ss (s/start-server! (atom {}) {} s/echo-handler)]
      (yasp/proxy! {:state          st
                    :allow-connect? (constantly true)
                    :now-ms         0
                    :session        "1"
                    :tls-str        server-keys}
                   {:op      "connect"
                    :payload (u/pr-str-safe {:host "localhost" :port @ss})})
      #_(t/is (= {:res "disallow-connect"})))))

