(ns nightcode.builders
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nightcode.dialogs :as dialogs]
            [nightcode.editors :as editors]
            [nightcode.lein :as lein]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.ui :as ui]
            [nightcode.utils :as utils]
            [seesaw.chooser :as chooser]
            [seesaw.color :as color]
            [seesaw.core :as s]
            [cross-parinfer.core :as cp]))

(declare show-builder!)

; keep track of open builders

(def builders (atom {}))

(defn get-builder
  [path]
  (when (contains? @builders path)
    (s/select (get-in @builders [path :view]) [:#build-console])))

(defn get-selected-builder
  []
  (get-builder (ui/get-project-path @ui/tree-selection)))

; actions for builder buttons

(defn set-android-sdk!
  [& _]
  (when-let [d (dialogs/show-open-dialog! (utils/read-pref :android-sdk))]
    (utils/write-pref! :android-sdk (.getCanonicalPath d))
    (show-builder! (ui/get-project-path @ui/tree-selection))))

(defn add-path
  [paths path]
  (if (and path (.endsWith path ".clj") (not (contains? (set paths) path)))
    (conj paths path)
    paths))

(defn sanitize-code
  [code]
  (let [result (-> code (cp/paren-mode 0 0) :text (cp/indent-mode 0 0))]
    (str "(do" \newline (:text result) \newline ")")))

(defn reload!
  [console]
  (when-let [path @ui/tree-selection]
    (->> (str (slurp path) \newline "nil")
         sanitize-code
         (.enterLine console))))

(defn eval!
  [console]
  (some->> (editors/get-editor-selected-text)
           sanitize-code
           (.enterLine console)))

; toggling functions

(defn toggle-builder-focus!
  [& _]
  (let [text-area (editors/get-selected-text-area)
        builder (some-> (get-selected-builder) .getTextArea)]
    (some-> (if (= text-area (.getFocusOwner @ui/root)) builder text-area)
            s/request-focus!)))

(defn toggle-visible!
  [{:keys [view]} path]
  (let [android-project? (lein/android-project? path)
        java-project? (lein/java-project? path)
        gwt-project? (lein/gwt-project? path)
        clojurescript-project? (lein/clojurescript-project? path)
        project-clj? (-> @ui/tree-selection
                         io/file
                         .getName
                         (= "project.clj"))
        buttons {:#run (not gwt-project?)
                 :#run-repl (not java-project?)
                 :#reload (not java-project?)
                 :#eval (not java-project?)
                 :#test (not java-project?)
                 :#sdk android-project?
                 :#auto clojurescript-project?
                 :#check-versions project-clj?}]
    (doseq [[id should-show?] buttons]
      (ui/config! view id :visible? should-show?))))

(defn toggle-color!
  [{:keys [view]} path]
  (let [project-map (lein/read-project-clj path)
        sdk (get-in project-map [:android :sdk-path])
        id :#sdk
        set? (and sdk (.exists (io/file sdk)))]
    (ui/config! view id :background (when-not set? (color/color :red)))))

(defn toggle-enable!
  [{:keys [view process last-reload]} path]
  (let [java-project? (lein/java-project? path)
        running? (not (nil? @process))
        buttons {:#run (not running?)
                 :#run-repl (not running?)
                 :#reload (not (nil? @last-reload))
                 :#eval (not (nil? @last-reload))
                 :#build (not running?)
                 :#test (not running?)
                 :#clean (not running?)
                 :#stop running?
                 :#check-versions (not running?)}]
    (doseq [[id should-enable?] buttons]
      (ui/config! view id :enabled? should-enable?))))

; create and show/hide builders for each project

(def ^:dynamic *widgets* [:run :run-repl :reload :eval :build :test
                          :clean :check-versions :stop
                          :sdk :auto])

(defn create-actions
  [path console build-pane process auto-process last-reload]
  {:run (fn [& _]
          (lein/run-project! process (ui/get-io! console) path)
          (when (lein/java-project? path)
            (reset! last-reload (System/currentTimeMillis))))
   :run-repl (fn [& _]
               (lein/run-repl-project! process (ui/get-io! console) path)
               (when (not (lein/java-project? path))
                 (reset! last-reload (System/currentTimeMillis))))
   :reload (fn [& _]
             (reload! console)
             (reset! last-reload (System/currentTimeMillis)))
   :eval (fn [& _]
           (eval! console))
   :build (fn [& _]
            (lein/build-project! process (ui/get-io! console) path))
   :test (fn [& _]
           (lein/test-project! process (ui/get-io! console) path))
   :clean (fn [& _]
            (lein/clean-project! process (ui/get-io! console) path))
   :check-versions (fn [& _]
                     (lein/check-versions-in-project!
                       process (ui/get-io! console) path))
   :stop (fn [& _]
           (lein/stop-process! process))
   :sdk set-android-sdk!
   :auto (fn [& _]
           (ui/config! build-pane :#auto :selected? (nil? @auto-process))
           (if (nil? @auto-process)
             (lein/cljsbuild-project!
               auto-process (ui/get-io! console) path)
             (lein/stop-process! auto-process)))})

(defn create-widgets
  [actions]
  {:run (s/button :id :run
                  :text (utils/get-string :run)
                  :listen [:action (:run actions)])
   :run-repl (s/button :id :run-repl
                       :text (utils/get-string :run-with-repl)
                       :listen [:action (:run-repl actions)])
   :reload (s/button :id :reload
                     :text (utils/get-string :reload)
                     :listen [:action (:reload actions)])
   :eval (s/button :id :eval
                   :text (utils/get-string :eval)
                   :listen [:action (:eval actions)])
   :build (s/button :id :build
                    :text (utils/get-string :build)
                    :listen [:action (:build actions)])
   :test (s/button :id :test
                   :text (utils/get-string :test)
                   :listen [:action (:test actions)])
   :clean (s/button :id :clean
                    :text (utils/get-string :clean)
                    :listen [:action (:clean actions)])
   :check-versions (s/button :id :check-versions
                             :text (utils/get-string :check-versions)
                             :listen [:action (:check-versions actions)])
   :stop (s/button :id :stop
                   :text (utils/get-string :stop)
                   :listen [:action (:stop actions)])
   :sdk (s/button :id :sdk
                  :text (utils/get-string :android-sdk)
                  :listen [:action (:sdk actions)])
   :auto (s/toggle :id :auto
                   :text (utils/get-string :auto-build)
                   :listen [:action (:auto actions)])})

;;dumb atom to keep track of our running processes, and
;;ensure we shutdown everything like spawned jvms.
(def processes (atom {}))
(defn push-process! [k]
  (if-let [p (get @processes k)]
    (do (lein/stop-process! p)
        p)
    (let [newp (atom nil)]
      (swap! processes assoc k newp)
      newp)))

(defn stop-all-processes! []
  (do (doseq [p (vals @processes)]
        (lein/stop-process! p))
      (reset! processes {})))

(defn create-builder
  [path]
  (let [; create console and the pane that will hold it
        console (editors/create-console path)
        build-pane (s/border-panel
                     :center (s/config! console :id :build-console))
        ; create atoms to hold important values
        process      (push-process! [:process path]) ;(atom nil)
        auto-process (push-process! [:auto-process path]) ;(atom nil)
        last-reload (atom nil)
        ; create the actions and widgets
        actions (create-actions path console build-pane
                                process auto-process last-reload)
        widgets (create-widgets actions)
        ; create the bar that holds the widgets
        widget-bar (ui/wrap-panel :items (map #(get widgets % %) *widgets*))]
    (utils/set-accessible-name! (.getTextArea console) :build-console)
    ; add the widget bar if necessary
    (when (pos? (count *widgets*))
      (doto build-pane
        (s/config! :north widget-bar)
        shortcuts/create-hints!
        (shortcuts/create-mappings! actions)))
    ; add console toggle mapping
    (shortcuts/create-mapping! @ui/root :build-console toggle-builder-focus!)
    ; refresh the builder when the process state changes
    (add-watch process
               :refresh-builder
               (fn [_ _ _ new-state]
                 (when (nil? new-state)
                   (reset! last-reload nil))
                 (show-builder! (ui/get-project-path @ui/tree-selection))))
    ; return a map describing the builder
    {:view build-pane
     :close-fn! (:stop actions)
     :should-remove-fn #(not (utils/project-path? path))
     :process process
     :last-reload last-reload}))

(defn show-builder!
  [path]
  (when-let [pane (ui/get-builder-pane)]
    ; create new builder if necessary
    (when (and path
               (utils/project-path? path)
               (not (contains? @builders path))
               (lein/valid-project? path))
      (when-let [builder (create-builder path)]
        (swap! builders assoc path builder)
        (.add pane (:view builder) path)))
    ; display the correct card
    (s/show-card! pane (if (contains? @builders path) path :default-card))
    ; modify pane based on the project
    (when-let [builder (get @builders path)]
      (doto builder
        (toggle-visible! path)
        (toggle-color! path)
        (toggle-enable! path)))))

(defn remove-builders!
  [path]
  (let [pane (s/select @ui/root [:#builder-pane])]
    (doseq [[builder-path {:keys [view close-fn! should-remove-fn]}] @builders]
      (when (or (utils/parent-path? path builder-path)
                (should-remove-fn))
        (swap! builders dissoc builder-path)
        (close-fn!)
        (editors/remove-watchers! builder-path)
        (.remove pane view)))))

; pane

(defn create-pane
  "Returns the pane with the builders."
  []
  (s/card-panel :id :builder-pane :items [["" :default-card]]))

; watchers

(add-watch ui/tree-selection
           :show-builder
           (fn [_ _ _ path]
             ; remove any builders that aren't valid anymore
             (remove-builders! nil)
             ; show the selected builder
             (show-builder! (ui/get-project-path path))))
