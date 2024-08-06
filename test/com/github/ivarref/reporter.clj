(ns com.github.ivarref.reporter
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.output :as output])
  (:import (java.io BufferedReader InputStreamReader)))

(defmulti result :type :hierarchy #'hierarchy/hierarchy)
(defmethod result :default [_])

(defmulti dots* :type :hierarchy #'hierarchy/hierarchy)
(defmethod dots* :default [_])

(defmethod dots* :pass [_]
  (t/with-test-out
    (print ".")
    (flush)))

(defmethod dots* :kaocha/fail-type [_]
  (t/with-test-out
    (print (output/colored :red "F"))
    (flush)))

(defmethod dots* :error [_]
  (t/with-test-out
    (print (output/colored :red "E"))
    (flush)))

(defmethod dots* :kaocha/pending [_]
  (t/with-test-out
    (print (output/colored :yellow "P"))
    (flush)))

(defmethod dots* :kaocha/begin-group [_]
  (t/with-test-out
    (print "(")
    (flush)))

(defmethod dots* :kaocha/end-group [_]
  (t/with-test-out
    (print ")")
    (flush)))

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
(def restore-cursor "\0337")
(def up-one-line "\033[1A")

(defn reserve-line [line]
  (str "\033[0;"
       line
       "r"))

(defmethod dots* :kaocha/begin-suite [_]
  (t/with-test-out
    (print (str "\n" ; ensure last line is available
                save-cursor
                (reserve-line (dec (line-count)))
                restore-cursor
                up-one-line))
    (flush)
    (println "begin-suite")
    (print "[")
    (flush)))

(defmethod dots* :kaocha/end-suite [_]
  (t/with-test-out
    (print "]")
    (flush)))

(defmethod dots* :summary [_]
  (t/with-test-out
    (println)))

(defonce dots
         #_"Reporter that prints progress as a sequence of dots and letters."
         [dots* result])

;import time
;import os
;from datetime import datetime
;
;columns, lines = os.get_terminal_size()
;
;
;def write(s):
;print(s, end="")
;# time.sleep(0.02)
;
;def hide_cursor():
;print("\033[?25l", end='', flush=True)
;
;# Function to show the cursor
;def show_cursor():
;print("\033[?25h", end='', flush=True)
;
;write("\n")  # Ensure the last line is available.
;write("\0337")  # Save cursor position
;write(f"\033[0;{lines-1}r")  # Reserve the bottom line
;write("\0338")  # Restore the cursor position
;write("\033[1A")  # Move up one line
;
;try:
;for i in range(255):
;time.sleep(0.1)
;write(f"Hello {i}\n")
;write("\0337")  # Save cursor position
;# hide_cursor()
;write(f"\033[0;{lines}r\033[{lines};0f")
;# unreserve bottom line
;# Move cursor to the bottom margin
;write(datetime.now().isoformat())  # Write the date
;write(f"\033[0;{lines-1}r") # add margin again (intellij bug)
;write("\0338")  # Restore cursor position
;# show_cursor()
;# write("\n")
;except KeyboardInterrupt:
;pass
;finally:
;write("\0337")  # Save cursor position
;write(f"\033[0;{lines}r")  # Drop margin reservation
;write(f"\033[{lines};0f")  # Move the cursor to the bottom line
;write("\033[0K")  # Clean that line
;write("\0338")  # Restore cursor position
;# show_cursor()
