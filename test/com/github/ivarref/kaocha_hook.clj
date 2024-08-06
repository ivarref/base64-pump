(ns com.github.ivarref.kaocha-hook
  (:require [kaocha.output :as output])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def formatter (DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn clear-screen! []
  (.print System/out "\033c") ;[H\033[2J")
  (.flush System/out))

(defn clear-screen [test test-plan]
  #_(clear-screen!)
  #_(println "Testing" (.format formatter (LocalDateTime/now)))
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
