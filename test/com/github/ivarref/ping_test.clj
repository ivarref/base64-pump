(ns com.github.ivarref.ping-test
  (:require [clj-commons.pretty.repl]
            [clojure.string :as str]
            [clojure.test :as t]
            [com.github.ivarref.locksmith :as locksmith]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp.tls-check :as tls-check]))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(t/deftest ping-test-no-tls
  (t/is (= {:res "pong"
            :tls "disabled"}
           (yasp/proxy! {:state          (atom {})
                         :allow-connect? (fn [_] true)}
                        {:op "ping"}))))

(defonce rand-keys (locksmith/generate-certs {}))

(t/deftest ping-test-mtls
  (t/is (= {:res "pong"
            :tls "valid"}
           (yasp/tls-proxy! {:state          (atom {})
                             :allow-connect? (fn [_] true)
                             :tls-str        (locksmith/server-keys rand-keys)}
                            {:op "ping"}))))

(def bad-server-keys
  (-> "-----BEGIN CERTIFICATE-----,XIIBYjCCAQmgAwIBAgIBATAKBggqhkjOPQQDAjAvMS0wKwYDVQQDDCRmOWUzNmRh,My1lMjQ4LTRiZjctOTMwNC1mMGRmYzMwYzkxZjIwHhcNMjQwODAxMTE1MzA5WhcN,MjUwODAxMTE1MzA5WjAvMS0wKwYDVQQDDCRmOWUzNmRhMy1lMjQ4LTRiZjctOTMw,NC1mMGRmYzMwYzkxZjIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASxTYlvjWHM,P32JXLyTVtdxhv7kz1TOMVodYJ3z4HMfsD7adA3rFhhaEsX1FDHDlm7gP7yZouD6,JCmkFWI2aIAooxYwFDASBgNVHRMBAf8ECDAGAQH/AgEAMAoGCCqGSM49BAMCA0cA,MEQCIBrlBVEA/rFjePbPppD9sCd+UbG9jpdQa8QLXSE3oocHAiBkYghkL5SC+k2Q,9nrPMAr190qNEGCb52G5s1+SVhlGfw==,-----END CERTIFICATE-----,-----BEGIN CERTIFICATE-----,MIIBSzCB8aADAgECAgEBMAoGCCqGSM49BAMCMC8xLTArBgNVBAMMJGY5ZTM2ZGEz,LWUyNDgtNGJmNy05MzA0LWYwZGZjMzBjOTFmMjAeFw0yNDA4MDExMTUzMDlaFw0y,NTA4MDExMTUzMDlaMC8xLTArBgNVBAMMJDM0MmFhYmRmLWI5NjAtNDIzMy1hZTk5,LTU2YTM2NTExZWY5ZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABPWjnZMPMFyX,285MAh3vJ8TXD8U4Q7o55CAReblQNndHHcYQvUDD9Ge+myaSKiqhdGoSDRuYGbEd,9iXPCKFOV0AwCgYIKoZIzj0EAwIDSQAwRgIhAPqzKBam5WSdvxVrcY0CmmtTeCza,2d6t2eLiSAVIPyPsAiEApiUDKJ4x04EHjnt8EsD1CaGCw/XfK4ETC8vPXF6acxM=,-----END CERTIFICATE-----,-----BEGIN PRIVATE KEY-----,MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAytBw6RkLGNG/LGBoG,3xXqsTmfvOEI4NOMdvpmjHysmw==,-----END PRIVATE KEY-----,"
      (str/replace "," "\n")))

(t/deftest ping-test-bad-keys
  (t/is (= {:res "pong"
            :tls "invalid"}
           (yasp/tls-proxy! {:state          (atom {})
                             :allow-connect? (fn [_] true)
                             :tls-str        bad-server-keys}
                            {:op "ping"}))))

(comment
  (time (tls-check/valid-tls-str? (locksmith/server-keys rand-keys))))

(t/deftest ping-test-bad-keys-2
  (t/is (= {:res "pong"
            :tls "invalid"}
           (yasp/tls-proxy! {:state          (atom {})
                             :allow-connect? (fn [_] true)}
                            {:op "ping"}))))
