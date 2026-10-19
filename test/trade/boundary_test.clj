(ns trade.boundary-test
  "FRONTIER TEST — decision math must never reappear in Python.
  Rule 1: decide/measure/verify = Clojure; sign/paint = Python. These
  assertions break the build if a decision artifact (signal state
  machines, z-scores, moving math, bar-driven targets) reappears in
  live/*.py, or if a deleted duplicate (engine.py, market_feed.py,
  virtual engines) comes back."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]))

(def ^:private live-dir "live")

(def ^:private forbidden
  ;; artifacts of DECISION math: the brain's job
  [{:pattern #"DipEngine|mr.?zscore|mr_zscore" :why "copied signal"}
   {:pattern #"def on_bar|self\.lrets|self\.state\s*=" :why "signal state machine"}
   {:pattern #"target[_ ]?vol|max.?lev|threshold" :why "signal params"}
   {:pattern #"math\.log\(.*\)\s*[-+]" :why "log-return math"}
   {:pattern #"market_feed" :why "deleted second WS owner"}])

(defn- py-files []
  ;; solo primer nivel: live/ contiene .venv-grvt con cientos de .py ajenos
  (->> (.listFiles (io/file live-dir))
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(.endsWith % ".py"))))

(deftest no-decision-math-in-python
  (doseq [f (py-files)
          {:keys [pattern why]} forbidden
          :let [line (some (fn [l] (when (re-find pattern l) l))
                           (line-seq (io/reader (io/file live-dir f))))]]
    (is (nil? line)
        (str "FRONTERA VIOLADA en live/" f " (" why "): " line))))

(deftest duplicates-stay-deleted
  (doseq [gone ["engine.py" "market_feed.py" "virtual_live.py" "okx_virtual.py"]]
    (is (not (.exists (io/file live-dir gone)))
        (str "el duplicado eliminado " gone " ha vuelto"))))

(deftest execution-modules-are-thin
  ;; the only Python files allowed in live/ — anything else needs a
  ;; frontier review before it ships
  (is (= #{"alpaca_live.py" "convert_tape.py" "executors.py" "feeds.py"
           "grvt_sdk_order.py" "recorder.py" "relay.py" "server.py"
           "store.py"}
         (set (py-files)))))
