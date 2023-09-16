(ns com.github.ivarref.yasp
  (:require [com.github.ivarref.yasp.impl :as impl])
  (:refer-clojure :exclude [future println]))                       ; no threads used :-)

(defonce default-state (atom {}))

(defn proxy!
  [{:keys [state now-ms] :as cfg} data]
  (assert (map? data) "Expected data to be a map")
  (impl/proxy-impl
    (assoc cfg :state (or state default-state)
               :now-ms (or now-ms (System/currentTimeMillis)))
    data))
