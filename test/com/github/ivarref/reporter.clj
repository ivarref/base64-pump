(ns com.github.ivarref.reporter
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kaocha.hierarchy :as hierarchy]
            [com.github.ivarref.bottom-line :as bl]
            [kaocha.output :as output]))

(defmulti result :type :hierarchy #'hierarchy/hierarchy)
(defmethod result :default [_])

(defmulti dots* :type :hierarchy #'hierarchy/hierarchy)
(defmethod dots* :default [_])

(defmethod dots* :pass [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! "." #_(output/colored :green "."))))

(defmethod dots* :kaocha/fail-type [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! (output/colored :red "F"))))

(defmethod dots* :error [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! (output/colored :red "E"))))

(defmethod dots* :kaocha/pending [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! (output/colored :yellow "P"))))

(defmethod dots* :kaocha/begin-group [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! "(")))

(defmethod dots* :kaocha/end-group [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! ")")))

(def run-count (atom 0))

(defn dashes [n]
  (str/join "-" (mapv (constantly "") (range n))))

(defmethod dots* :kaocha/begin-suite [_]
  (t/with-test-out
    (bl/init!)
    (bl/print-bottom-line-continuous! "[")
    (println (dashes 40) (swap! run-count inc) (dashes 40))))

(defmethod dots* :kaocha/end-suite [_]
  (t/with-test-out
    (bl/print-bottom-line-continuous! "]")))

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
