(ns com.github.ivarref.yasp.utils
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream Closeable InputStream OutputStream)
           (java.net SocketTimeoutException)
           (java.util Base64)))

; Private API, subject to change

(defonce lock (Object.))

(defn atomic-println [& args]
  (locking lock
    (binding [*out* *err*]
      (apply clojure.core/println args)
      (flush))))

(defn pr-str-inner [x]
  (binding [*print-dup* false
            *print-meta* false
            *print-readably* true
            *print-length* nil
            *print-level* nil
            *print-namespace-maps* false]
    (pr-str x)))

(defn pr-str-safe [x]
  (try
    (if (= x (edn/read-string (pr-str-inner x)))
      (pr-str-inner x)
      (throw (ex-info (str "Could not read-string") {:input x})))
    (catch Throwable t
      (log/error t "could not read-string:" (ex-message t))
      (throw t))))

(defn copy-bytes [in-bytes ^OutputStream os]
  (with-open [bais (ByteArrayInputStream. in-bytes)]
    (io/copy bais os)
    (.flush os)))

(defn bytes->base64-str [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn base64-str->bytes [^String base64-str]
  (.decode (Base64/getDecoder) base64-str))

(defn read-max-bytes
  "Read `max-bytes` from inputstream `is`.

  Returns a byte array of 0 or more bytes.
  Returns nil when EOF is reached and no byte was read.

  Returns the byte array if a SocketTimeoutException occurs."
  [^InputStream is max-bytes]
  (let [out (ByteArrayOutputStream.)
        eof? (atom false)]
    (loop [c 0]
      (when-let [r (try
                     (let [available (.available is)]
                       (cond (and (= 0 available) (not= c 0))
                             (do
                               (log/trace "Would block, return bytes so far")
                               nil)

                             :else
                             (do
                               (when (and (= 0 available) (= c 0))
                                 (log/trace "Will (probably) block"))
                               (.read is))))
                     (catch SocketTimeoutException ste
                       nil))]
        (if (= r -1)
          (reset! eof? true)
          (do
            (.write out ^Integer r)
            (when (not= max-bytes c)
              (recur (inc c)))))))
    (let [byte-array (.toByteArray out)]
      (if (and @eof?
               (= 0 (count byte-array)))
        nil
        byte-array))))

(comment
  (String. (read-max-bytes
             (ByteArrayInputStream. (.getBytes "Hello World"))
             5)))

(defn close-silently! [^Closeable c]
  (when (and c (instance? Closeable c))
    (try
      (.close c)
      (catch Throwable t
        nil))))

