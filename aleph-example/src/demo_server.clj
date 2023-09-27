(ns demo-server
  (:require [cheshire.core :as json]
            [aleph.http :as http]
            [clojure.tools.logging :as log]
            [nrepl.server :as nrepl]
            [com.github.ivarref.yasp :as yasp])
  (:import (java.io InputStream InputStreamReader)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(def proxy-config
  {:allow-connect? (fn [{:keys [host port]}]
                     (and (= host "127.0.0.1")
                          (= port 7777)))
   :tls-file "server.keys"})

(defn start-nrepl! []
  (try
    (nrepl/start-server :port 7777
                        :bind "127.0.0.1")
    (log/info "nREPL server running at 127.0.0.1:7777")
    (catch Throwable t
      (log/error t "Could not start nREPL server" (ex-message t)))))

(defn handler [{:keys [uri body]}]
  (cond
    (= "/proxy" uri)
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (json/generate-string
                (yasp/tls-proxy!
                  proxy-config
                  (json/decode-stream
                    (InputStreamReader. ^InputStream body StandardCharsets/UTF_8) keyword)))}
    (= "/" uri)
    {:status  200
     :headers {"content-type" "text/plain"}
     :body    "Demo server"}

    :else
    {:status  404
     :headers {"content-type" "text/plain"}
     :body    "Not found"}))

(defn run-server [_]
  (start-nrepl!)
  (http/start-server handler
                     {:socket-address (InetSocketAddress. "127.0.0.1" 8080)})
  (log/info "HTTP Server running at http://127.0.0.1:8080")
  @(promise))

; curl -X POST "http://localhost:8080/proxy" -H 'Content-Type: application/json' -d '{"op":"ping"}'
