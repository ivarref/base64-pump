(ns com.github.ivarref.demo-server-no-mtls
  (:require [aleph.http]
            [cheshire.core]
            [com.github.ivarref.yasp]))

(defn handler [{:keys [uri body request-method]}]
  (cond
    (and (= "/proxy" uri) (= :post request-method) (instance? java.io.InputStream body))
    {:status  200
     :headers {"content-type" "application/json; charset=UTF-8"}
     :body    (cheshire.core/generate-string
                (com.github.ivarref.yasp/proxy!
                  {:allow-connect? (fn [{:keys [host port]}]
                                     (and (= host "127.0.0.1")
                                          (= port 22)))}
                  (cheshire.core/decode-stream
                    (java.io.InputStreamReader. ^java.io.InputStream body java.nio.charset.StandardCharsets/UTF_8)
                    keyword)))}

    (= "/proxy" uri)
    {:status  400
     :headers {"content-type" "application/json"}
     :body    (cheshire.core/generate-string {:message "Bad Request"})}

    (= "/" uri)
    {:status  200
     :headers {"content-type" "text/plain"}
     :body    "Demo server"}

    :else
    {:status  404
     :headers {"content-type" "text/plain"}
     :body    "Not found"}))

(defn start-server! []
  (aleph.http/start-server (fn [ctx] (handler ctx))
                           {:socket-address (java.net.InetSocketAddress. "127.0.0.1" 8080)})
  (println "HTTP Server running at http://127.0.0.1:8080"))

; curl -X POST http://localhost:8080/proxy -H "Content-Type: application/json" -d '{"op" : "ping"}'
