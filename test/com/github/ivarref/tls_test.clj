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
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLException)))

(set! *warn-on-reflection* true)

(t/use-fixtures :each u/with-fut)

#_(clj-commons.pretty.repl/install-pretty-exceptions)

(comment
  #_(defonce st (atom {})))

(defonce old-state (atom nil))

(defn break []
  (log/info (str/join "*" (repeat 60 ""))))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out)
  (break))

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (locksmith/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

(defn tls-pump [proxy-cfg {:keys [^Socket sock]}]
  (try
    (with-open [tls-in (BufferedInputStream. (.getInputStream sock))
                tls-out (BufferedOutputStream. (.getOutputStream sock))]
      (loop []
        (when-let [chunk (u/read-max-bytes tls-in 64000)]
          (let [resp (yasp/proxy! proxy-cfg
                                  {:op      "send"
                                   :session (get proxy-cfg :session)
                                   :payload (u/bytes->base64-str chunk)})]
            (when (= "ok-send" (get resp :res))
              (u/write-bytes (u/base64-str->bytes (get resp :payload))
                             tls-out)))
          (recur))))
    (finally)))

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
            (.setSoTimeout sock 1000)
            (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                        out (PrintWriter. (BufferedOutputStream. (.getOutputStream sock)) true StandardCharsets/UTF_8)]
              (.println out "Hello World!")
              (t/is (= "Hello World!" (.readLine in)))

              (.println out "Hallo, 你好世界")
              (t/is (= "Hallo, 你好世界" (.readLine in)))))))
      (finally
        (yasp/close! st)
        (reset! old-state @st)))))

(t/deftest bad-tls-str-server
  (let [st (atom {})
        proxy-cfg {:state          st
                   :allow-connect? (constantly true)
                   :now-ms         0
                   :session        "1"
                   :tls-str        "asdf"
                   :tls-port       1919}]
    (try
      (with-open [echo-server (s/start-server! (atom {}) {:local-port 9999} s/echo-handler)]
        (t/is (= "error"
                 (:res (yasp/proxy! proxy-cfg {:op      "connect"
                                               :payload (u/pr-str-safe {:host "127.0.0.1" :port @echo-server})})))))
      (finally
        (yasp/close! st)))))

(t/deftest bad-tls-client
  (let [st (atom {})
        kp1 (gen-key-pair)
        kp2 (gen-key-pair)
        proxy-cfg {:state          st
                   :allow-connect? (constantly true)
                   :now-ms         0
                   :session        "1"
                   :tls-str        (first kp1)
                   :tls-port       1919}]
    (try
      (with-open [echo-server (s/start-server! (atom {}) {:local-port 9999} s/echo-handler)
                  tls-client (s/start-server! (atom {}) {:local-port 8888} (partial tls-pump proxy-cfg))
                  tls-client2 (s/start-server! (atom {}) {:local-port 7777} (partial tls-pump (assoc proxy-cfg :session "2")))]
        (let [tls-1 (tls/ssl-context-or-throw (second kp1) nil)
              tls-2 (tls/ssl-context-or-throw (second kp2) nil)
              _ (yasp/proxy! proxy-cfg {:op      "connect"
                                        :payload (u/pr-str-safe {:host "127.0.0.1" :port @echo-server})})]
          (try
            (with-open [sock (tls/socket tls-2 "localhost" @tls-client 3000)]
              (.setSoTimeout sock 1000)
              (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                          out (PrintWriter. (BufferedOutputStream. (.getOutputStream sock)) true StandardCharsets/UTF_8)]
                (let [error? (atom false)]
                  (try
                    (.readLine in)
                    (catch Throwable t
                      (t/is (true? (instance? SSLException t)))
                      (reset! error? true)))
                  (t/is (true? @error?)))))

            (yasp/proxy! (assoc proxy-cfg :session "2")
                         {:op      "connect"
                          :payload (u/pr-str-safe {:host "127.0.0.1" :port @echo-server})})

            (with-open [sock (tls/socket tls-1 "localhost" @tls-client2 3000)]
              (.setSoTimeout sock 1000)
              (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                          out (PrintWriter. (BufferedOutputStream. (.getOutputStream sock)) true StandardCharsets/UTF_8)]
                (.println out "Hallo, 你好世界")
                (t/is (= "Hallo, 你好世界" (.readLine in)))))
            (catch Throwable t
              (log/info "Error:" (ex-message t))))))
      (finally
        (yasp/close! st)
        (reset! old-state @st)))))

(defn keep-sending [{:keys [^Socket sock closed?]}]
  (try
    (with-open [in (BufferedInputStream. (.getInputStream sock))
                out (BufferedOutputStream. (.getOutputStream sock))]
      (loop []
        (u/write-bytes (.getBytes "Hello\n" StandardCharsets/UTF_8) out)
        (recur)))
    (catch Throwable t
      (if-not (or (.isClosed sock) @closed?)
        (log/warn "keep-sending handler error:" (ex-message t))
        (log/debug "keep-sending handler error:" (ex-message t))))
    (finally
      (log/debug "keep-sending handler exiting"))))

(t/deftest pump-threads-exits
  (let [st (atom {})
        kp1 (gen-key-pair)
        proxy-cfg {:state          st
                   :allow-connect? (constantly true)
                   :now-ms         0
                   :session        "1"
                   :tls-str        (first kp1)
                   :tls-port       1919}]
    (try
      (with-open [echo-server (s/start-server! (atom {}) {:local-port 9999} keep-sending)
                  tls-client (s/start-server! (atom {}) {:local-port 8888} (partial tls-pump proxy-cfg))]
        (let [tls-1 (tls/ssl-context-or-throw (second kp1) nil)
              _ (yasp/proxy! proxy-cfg {:op      "connect"
                                        :payload (u/pr-str-safe {:host "127.0.0.1" :port @echo-server})})]
          (try
            (with-open [sock (tls/socket tls-1 "localhost" @tls-client 3000)]
              (.setSoTimeout sock 1000)
              (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))]
                (dotimes [_ 1000]
                  (t/is (= "Hello" (.readLine in))))))
            (catch Throwable t
              (log/info "Error:" (ex-message t))))))
      (finally
        (yasp/close! st)))))
