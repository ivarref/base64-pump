(ns com.github.ivarref.tls-test
  (:require [clj-commons.pretty.repl]
            [clojure.string :as str]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [com.github.ivarref.locksmith :as locksmith]
            [com.github.ivarref.server :as s]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp.tls :as tls]
            [com.github.ivarref.yasp.utils :as u])
  (:import (java.io BufferedInputStream BufferedOutputStream BufferedReader InputStreamReader PrintWriter)
           (java.net Socket)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (locksmith/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

(comment
  #_(defonce st (atom {})))

(defonce old-state (atom nil))

(defn break []
  (log/info (str/join "*" (repeat 60 ""))))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out)
  (break))

(defn tls-pump [proxy-cfg {:keys [^Socket sock]}]
  (with-open [tls-in (BufferedInputStream. (.getInputStream sock))
              tls-out (BufferedOutputStream. (.getOutputStream sock))]
    (loop []
      (when-let [chunk (u/read-max-bytes tls-in 64000)]
        (let [resp (yasp/proxy! proxy-cfg
                                {:op      "send"
                                 :session "1"
                                 :payload (u/bytes->base64-str chunk)})]
          (when (= "ok-send" (get resp :res))
            (u/write-bytes (u/base64-str->bytes (get resp :payload))
                           tls-out)))
        (recur)))))

(t/deftest tls-hello
  (let [st (atom {})
        [server-keys client-keys] (gen-key-pair)
        proxy-cfg {:state          st
                   :allow-connect? (constantly true)
                   :now-ms         0
                   :session        "1"
                   :tls-str        server-keys
                   :tls-port       1919}]
    (try
      (with-open [echo-server (s/start-server! (atom {}) {:local-port 9999} s/echo-handler)
                  tls-client (s/start-server! (atom {}) {:local-port 9876} (partial tls-pump proxy-cfg))]
        (let [tls-context (tls/ssl-context-or-throw client-keys nil)
              _ (yasp/proxy! proxy-cfg {:op      "connect"
                                        :payload (u/pr-str-safe {:host "127.0.0.1" :port @echo-server})})]
          (with-open [sock (tls/socket tls-context "localhost" 9876 3000)]
            (.setSoTimeout sock 3000)
            ;(log/info "Start connect TLS socket")
            (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                        out (PrintWriter. (BufferedOutputStream. (.getOutputStream sock)) true StandardCharsets/UTF_8)]
              (.println out "Hello World!")
              (t/is (= "Hello World!" (.readLine in)))

              (.println out "Hallo, 你好世界")
              (t/is (= "Hallo, 你好世界" (.readLine in)))))))
        #_(reset! old-state @st)
        #_(t/is (= {:res "disallow-connect"}))
      (finally
        (break)
        (yasp/close! st)
        (reset! old-state @st)))))
;(impl/expire-connections! st (System/currentTimeMillis))
;(some->> (get-in @st [:tls-proxy "127.0.0.1" 9999 :state])
;         (s/close!))))))

