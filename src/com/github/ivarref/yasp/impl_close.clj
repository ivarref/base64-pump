(ns com.github.ivarref.yasp.impl-close
  (:require [com.github.ivarref.yasp.utils :as u])
  (:import (clojure.lang IAtom2)))

(defn handle-close! [{:keys [state]} {:keys [session]}]
  (assert (instance? IAtom2 state))
  (assert (string? session))
  (if-let [sess (get-in @state [:sessions session])]
    (do (u/close-silently! (get sess :in))
        (u/close-silently! (get sess :out))
        (u/close-silently! (get sess :socket))
        (swap! state update :sessions dissoc session)
        {:res "ok-close"})
    {:res "unknown-session"}))
