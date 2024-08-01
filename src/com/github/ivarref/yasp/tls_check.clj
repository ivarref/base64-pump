(ns com.github.ivarref.yasp.tls-check
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.yasp.tls :as tls])
  (:import (javax.net.ssl SSLContext)))

(defn valid-tls-str? [tls-str]
  (if (and (string? tls-str)
           (str/includes? tls-str "-----BEGIN CERTIFICATE-----")
           (str/includes? tls-str "-----END CERTIFICATE-----")
           (str/includes? tls-str "-----BEGIN PRIVATE KEY-----")
           (str/includes? tls-str "-----END PRIVATE KEY-----"))
    (try
      (instance? SSLContext (tls/ssl-str-context tls-str))
      (catch Exception e
        (log/warn "Could not create ssl-context:" (ex-message e))
        false))
    false))
