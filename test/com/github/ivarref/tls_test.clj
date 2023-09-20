(ns com.github.ivarref.tls-test
  (:require [clj-commons.pretty.repl]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [com.github.ivarref.locksmith :as locksmith]
            [com.github.ivarref.server :as s]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp.impl :as impl]
            [com.github.ivarref.yasp.tls :as tls]
            [com.github.ivarref.yasp.utils :as u]))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (locksmith/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

(comment
  #_(defonce st (atom {})))

(defonce old-state (atom nil))

(t/deftest tls-hello
  (let [st (atom {})
        [server-keys client-keys] (gen-key-pair)]
    (try
      (with-open [ss (s/start-server! (atom {}) {:local-port 9999} s/echo-handler)]
        (let [v (yasp/proxy! {:state          st
                              :allow-connect? (constantly true)
                              :now-ms         0
                              :session        "1"
                              :tls-str        server-keys
                              :tls-port       1919}
                             {:op      "connect"
                              :payload (u/pr-str-safe {:host "127.0.0.1" :port @ss})})]
          (log/info "v is:" v))
        #_(Thread/sleep 1000)
        #_(reset! old-state @st)
        #_(t/is (= {:res "disallow-connect"})))
      (finally
        (yasp/close! st)
        (reset! old-state @st)))))
        ;(impl/expire-connections! st (System/currentTimeMillis))
        ;(some->> (get-in @st [:tls-proxy "127.0.0.1" 9999 :state])
        ;         (s/close!))))))

