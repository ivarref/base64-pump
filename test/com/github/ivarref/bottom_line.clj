(ns com.github.ivarref.bottom-line
  (:require [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader)))

(defn rows-cols []
  (let [^ProcessBuilder processbuilder (ProcessBuilder. ["sh" "-c" "stty size < /dev/tty"])
        ^Process process (.start processbuilder)
        line (atom nil)]
    (with-open [^BufferedReader reader (BufferedReader. (InputStreamReader. (.getInputStream process)))]
      (reset! line (.readLine reader)))
    (.waitFor process)
    (assert (= 0 (.exitValue process)))
    (let [line-str @line
          [rows cols] (str/split line-str #" ")]
      [(Integer/parseInt rows)
       (Integer/parseInt cols)])))

(defn line-count []
  (first (rows-cols)))

(def save-cursor "\0337")
(def restore-cursor "\0338")
(def up-one-line "\033[1A")

(defn reserve-line [line]
  (str "\033[0;"
       line
       "r"))

(defn reserve-bottom-line []
  (reserve-line (dec (line-count))))

(defn unreserve-bottom-line []
  (reserve-line (line-count)))

(defn bottom-line [s]
  (str save-cursor
       (unreserve-bottom-line)
       (str "\033[" (line-count) ";0f")                     ; Move cursor to the bottom margin
       s                                                    ; actual line...
       (str "\033[0K")                                      ; clear to end of line
       (reserve-bottom-line)
       restore-cursor))

(defn print-bottom-line! [s]
  (print (bottom-line s))
  (flush))

(def line-state (atom []))

(defn print-bottom-line-continuous! [s]
  (print-bottom-line!
    (str (str/join "" @line-state)
         s))
  (swap! line-state conj s))

(defn init! []
  (println "")
  (print (str save-cursor
              (reserve-bottom-line)
              restore-cursor
              up-one-line))
  (flush)
  (reset! line-state []))

