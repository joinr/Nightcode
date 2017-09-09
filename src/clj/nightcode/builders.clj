(ns nightcode.builders
  (:require [nightcode.shortcuts :as shortcuts]
            [nightcode.lein :as l]
            [nightcode.spec :as spec]
            [nightcode.utils :as u]
            [nightcode.process :as proc]
            [clojure.spec.alpha :as s :refer [fdef]]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader]
           [javafx.scene.web WebEngine]
           [javafx.scene.control Button]
           [java.io PipedWriter PipedReader PrintWriter]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [nightcode.utils Bridge]))
;;possible problem here.
(defn pipe-into-console! [^WebEngine engine in-pipe]
  (let [ca (char-array 256)]
    (.start
      (Thread.
        (fn []
          (loop []
            (when-let [read (try (.read in-pipe ca)
                              (catch Exception _))]
              (when (pos? read)
                (let [s (u/remove-returns (String. ca 0 read))]
                  (Platform/runLater
                    (fn []
                      (-> engine
                          (.executeScript "window")
                          (.call "append" (into-array [s])))))
                  (Thread/sleep 100) ; prevent application thread from being flooded
                  (recur))))))))))

(defn create-pipes []
  (let [out-pipe (PipedWriter.)
        in (LineNumberingPushbackReader. (PipedReader. out-pipe))
        pout (PipedWriter.)
        out (PrintWriter. pout)
        in-pipe (PipedReader. pout)]
    {:in in :out out :in-pipe in-pipe :out-pipe out-pipe}))

(defn start-builder-thread! [webview pipes finish-str work-fn]
  (let [engine (.getEngine webview)
        {:keys [in-pipe in out]} pipes]
    (pipe-into-console! engine in-pipe)
    (.start
      (Thread.
        (fn []
          (binding [*out* out
                    *err* out
                    *in* in]
            (try
              (work-fn)
              (catch Exception e (some-> (.getMessage e) println))
              (finally (some-> finish-str println)))))))))

(defn start-builder-process!
  ([webview pipes process start-str project-path args]
   (start-builder-process! webview pipes process start-str
     #(proc/start-java-process! process project-path args)))
  ([webview pipes process start-str start-fn]
   (proc/stop-process! process)
   (start-builder-thread! webview pipes "\n=== Finished ==="
     (fn []
       (some-> start-str println)
       (start-fn)))))

(defn stop-builder-process! [runtime-state project-path]
  (when-let [process (get-in runtime-state [:processes project-path])]
    (proc/stop-process! process)))

(def err-out *out*)
(defn init-console! [project-path runtime-state-atom webview pipes web-port cb]
  (doto webview
    (.setVisible true)
    (.setContextMenuEnabled false))
  (let [engine (.getEngine webview)]
    (.setOnStatusChanged engine
      (reify EventHandler
        (handle [this event]
          (let [bridge (proxy [Bridge] []
                         (onload []
                           (try
                             (cb)
                             (catch Exception e (.printStackTrace e))))
                         (onautosave [])
                         (onchange [])
                         (onenter [text]
                           (do (binding [*out* err-out] ;;pre-emptively debug repl output
                                  (println [:repl-is-getting! text]))
                               (doto (:out-pipe pipes)
                                 (.write text)
                                 (.flush))))
                         )]
            ; prevent bridge from being GC'ed
            (swap! runtime-state-atom update :bridges assoc project-path bridge)
            (-> engine
                (.executeScript "window")
                (.setMember "java" bridge))))))
    (.load engine (str "http://localhost:" web-port "/paren-soup.html"))))

(def index->system {0 :boot 1 :lein})
(def system->index (set/map-invert index->system))

(defn get-tab [pane system]
  (-> (.lookup pane "#build_tabs")
      .getTabs
      (.get (system->index system))))

(defn get-selected-build-system [pane]
  (-> (.lookup pane "#build_tabs") .getSelectionModel .getSelectedIndex index->system))

(defn select-build-system! [pane system ids]
  (-> (.lookup pane "#build_tabs") .getSelectionModel (.select (system->index system)))
  (-> (get-tab pane system) .getContent (shortcuts/add-tooltips! ids)))

(defn refresh-builder! [webview repl? pref-state]
  (doto (.getEngine webview)
    (.executeScript (if repl? "initConsole(true)" "initConsole(false)"))
    (.executeScript (case (:theme pref-state)
                      :dark "changeTheme(true)"
                      :light "changeTheme(false)"))
    (.executeScript (format "setTextSize(%s)" (:text-size pref-state)))))

(def ^:const ids [:.run :.build :.run-with-repl :.reload-file :.reload-selection :.clean :.test :.stop])
(def ^:const disable-when-running [:.run :.build :.run-with-repl :.clean :.test :.custom])
(def ^:const disable-when-not-running [:.reload-file :.reload-selection :.stop])
(def ^:const custom-task-ids [:.run :.build])

(defn update-when-process-changes! [pane process-running?]
  (doseq [id disable-when-running]
    (doseq [node (.lookupAll pane (name id))]
      (.setDisable node process-running?)))
  (doseq [id disable-when-not-running]
    (doseq [node (.lookupAll pane (name id))]
      (.setDisable node (not process-running?)))))

(defn get-builder-webview [pref-state runtime-state]
  (when-let [project-path (u/get-project-path pref-state)]
    (when-let [pane (get-in runtime-state [:project-panes project-path])]
      (when-let [system (get-selected-build-system pane)]
        (let [tab-content (.getContent (get-tab pane system))]
          (.lookup tab-content "#build_webview"))))))

(defn start-builder! [pref-state runtime-state-atom start-str cmd]
  (when-let [project-path (u/get-project-path pref-state)]
    (when-let [pane (get-in @runtime-state-atom [:project-panes project-path])]
      (when-let [system (get-selected-build-system pane)]
        (let [tab-content (.getContent (get-tab pane system))
              webview (.lookup tab-content "#build_webview")
              pipes (create-pipes)
              process (or (get-in @runtime-state-atom [:processes project-path])
                          (doto (atom nil)
                            (add-watch :process-changed
                              (fn [_ _ _ new-process]
                                (update-when-process-changes! pane (some? new-process))))))]
          (init-console! project-path runtime-state-atom webview pipes (:web-port @runtime-state-atom)
            (fn []
              (refresh-builder! webview (= cmd "repl") pref-state)
              (start-builder-process! webview pipes process start-str
                (case system
                  :lein #(proc/start-java-process! process project-path [l/class-name cmd])
                  :boot #(proc/start-process! process project-path [(u/get-boot-path) "--no-colors" cmd])))))
          (swap! runtime-state-atom assoc-in [:processes project-path] process))))))

(defn stop-builder! [pref-state runtime-state]
  (when-let [project-path (u/get-project-path pref-state)]
    (stop-builder-process! runtime-state project-path)))

(defn show-boot-buttons! [pane path pref-state runtime-state-atom]
  (when-let [task-buttons (some-> (get-tab pane :boot) .getContent (.lookup "#tasks"))]
    (when-let [perm-tasks (.lookup task-buttons "#permanent_tasks")]
      (doto task-buttons
        shortcuts/hide-tooltips!
        (shortcuts/remove-tooltips! custom-task-ids))
      (-> task-buttons .getChildren .clear)
      (doseq [task-name (u/get-boot-tasks path)]
        (let [btn (Button.)]
          (doto (.getStyleClass btn)
            (.add task-name)
            (.add "custom"))
          (doto btn
            (.setText (->> (str/split task-name #"-")
                           (map str/capitalize)
                           (str/join " ")))
            (.setOnAction
              (reify EventHandler
                (handle [this event]
                  (start-builder! pref-state runtime-state-atom (str "Starting " task-name " task...") task-name)))))
          (-> task-buttons .getChildren (.add btn))))
      (-> task-buttons .getChildren (.add perm-tasks))
      ; for certain custom tasks, add tooltips
      (doto task-buttons
        (shortcuts/add-tooltips! custom-task-ids)
        shortcuts/hide-tooltips!))))

(defn init-builder! [pane path pref-state runtime-state-atom]
  (let [systems (u/build-systems path)]
    ; add/remove tooltips
    (.addListener (-> (.lookup pane "#build_tabs") .getSelectionModel .selectedItemProperty)
      (reify ChangeListener
        (changed [this observable old-value new-value]
          (some-> old-value .getContent shortcuts/hide-tooltips!)
          (some-> old-value .getContent (shortcuts/remove-tooltips! ids))
          (some-> new-value .getContent (shortcuts/add-tooltips! ids)))))
    ; select/disable build tabs
    (cond
      (:boot systems) (do
                        (select-build-system! pane :boot ids)
                        (show-boot-buttons! pane path pref-state runtime-state-atom))
      (:lein systems) (select-build-system! pane :lein ids))
    (.setDisable (get-tab pane :boot) (not (:boot systems)))
    (.setDisable (get-tab pane :lein) (not (:lein systems)))
    ; init the tabs
    (doseq [system systems]
      (.setDisable (get-tab pane system) false))))

; specs

(fdef pipe-into-console!
  :args (s/cat :engine any? :in-pipe #(instance? java.io.Reader %)))

(fdef create-pipes
  :args (s/cat)
  :ret map?)

(fdef start-builder-thread!
  :args (s/cat :webview spec/node? :pipes map? :finish-str (s/nilable string?) :work-fn fn?))

(fdef start-builder-process!
  :args (s/alt
          :args (s/cat  :webview spec/node? :pipes map? :process spec/atom? :start-str (s/nilable string?) :project-path string? :args (s/coll-of string?))
          :start-fn (s/cat :webview spec/node? :pipes map? :process spec/atom? :start-str (s/nilable string?) :start-fn fn?)))

(fdef stop-builder-process!
  :args (s/cat :runtime-state map? :project-path string?))

(fdef init-console!
  :args (s/cat :project-path string? :runtime-state-atom spec/atom? :webview spec/node? :pipes map? :web-port number? :callback fn?))

(fdef get-tab
  :args (s/cat :pane spec/pane? :system keyword?)
  :ret #(instance? javafx.scene.control.Tab %))

(fdef get-selected-build-system
  :args (s/cat :pane spec/pane?)
  :ret (s/nilable keyword?))

(fdef select-build-system!
  :args (s/cat :pane spec/pane? :system keyword? :ids (s/coll-of keyword?)))

(fdef refresh-builder!
  :args (s/cat :webview spec/node? :repl? boolean? :pref-state map?))

(fdef build-system->class-name
  :args (s/cat :system keyword?)
  :ret string?)

(fdef update-when-process-changes!
  :args (s/cat :pane spec/pane? :process-running? boolean?))

(fdef get-builder-webview
  :args (s/cat :pref-state map? :runtime-state map?)
  :ret spec/node?)

(fdef start-builder!
  :args (s/cat :pref-state map? :runtime-state-atom spec/atom? :start-str string? :cmd string?))

(fdef stop-builder!
  :args (s/cat :pref-state map? :runtime-state map?))

(fdef show-boot-buttons!
  :args (s/cat :pane spec/pane? :path string? :pref-state map? :runtime-state-atom spec/atom?))

(fdef init-builder!
  :args (s/cat :pane spec/pane? :path string? :pref-state map? :runtime-state-atom spec/atom?))

