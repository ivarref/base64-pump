(ns com.github.ivarref.kaocha-hook
  (:require [kaocha.output :as output])
  (:import (java.util Date)))

(defn clear-screen! []
  (.print System/out "\033[H\033[2J")
  (.flush System/out))

(defn clear-screen [test test-plan]
  (clear-screen!)
  test)

#_(defn pre-test [test test-plan]
    #_(println "Begin" (str (:kaocha.testable/id test)))
    test)

#_(defn test-error? [test]
    (pos-int? (:kaocha.result/fail test)))

#_(defn post-test [test test-plan]
    (println "Testing" (str (:kaocha.testable/id test)) "..."
             (if (test-error? test)
               (output/colored :red " FAIL")
               "OK"))
    test)

#_(defn silent-reporter [_]
    nil)
