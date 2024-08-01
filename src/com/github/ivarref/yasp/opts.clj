(ns com.github.ivarref.yasp.opts)

(def allow-connect? :allow-connect?)
(def connect-timeout-ms :connect-timeout-ms)
(def socket-timeout-ms :socket-timeout-ms)
(def chunk-size-bytes :chunk-size-bytes)

(def connect-timeout-ms-default 3000)
(def socket-timeout-ms-default 100)
(def chunk-size-bytes-default 65535)
