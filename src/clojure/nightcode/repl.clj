(ns nightcode.repl
  (:require [nightcode.editors :as editors]
            [nightcode.lein :as lein]
            [nightcode.sandbox :as sandbox]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.ui :as ui]
            [nightcode.utils :as utils]
            [seesaw.core :as s]
            [clojure.pprint :as pprint])
  (:import [clojure.lang LineNumberingPushbackReader]))

(in-ns 'clojure.main)
;;Added the ability to provide a request-exit option, rather than
;;the autogenerated object, which allows the caller to define a value
;that stops repl loop.
(defn repl
  "Generic, reusable, read-eval-print loop. By default, reads from *in*,
  writes to *out*, and prints exception summaries to *err*. If you use the
  default :read hook, *in* must either be an instance of
  LineNumberingPushbackReader or duplicate its behavior of both supporting
  .unread and collapsing CR, LF, and CRLF into a single \\newline. Options
  are sequential keyword-value pairs. Available options and their defaults:

     - :init, function of no arguments, initialization hook called with
       bindings for set!-able vars in place.
       default: #()

     - :need-prompt, function of no arguments, called before each
       read-eval-print except the first, the user will be prompted if it
       returns true.
       default: (if (instance? LineNumberingPushbackReader *in*)
                  #(.atLineStart *in*)
                  #(identity true))

     - :prompt, function of no arguments, prompts for more input.
       default: repl-prompt

     - :flush, function of no arguments, flushes output
       default: flush

     - :read, function of two arguments, reads from *in*:
         - returns its first argument to request a fresh prompt
           - depending on need-prompt, this may cause the repl to prompt
             before reading again
         - returns its second argument to request an exit from the repl
         - else returns the next object read from the input stream
       default: repl-read

     - :eval, function of one argument, returns the evaluation of its
       argument
       default: eval

     - :print, function of one argument, prints its argument to the output
       default: prn

     - :caught, function of one argument, a throwable, called when
       read, eval, or print throws an exception or error
       default: repl-caught
       :request-exit, value that indicates the repl should stop looping."
  [& options]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
  (let [{:keys [init need-prompt prompt flush read eval print caught request-exit]
         :or {init        #()
              need-prompt (if (instance? LineNumberingPushbackReader *in*)
                            #(.atLineStart ^LineNumberingPushbackReader *in*)
                            #(identity true))
              prompt      clojure.main/repl-prompt
              flush       flush
              read        clojure.main/repl-read
              eval        eval
              print       prn
              caught      clojure.main/repl-caught}}
        (apply hash-map options)
        request-prompt (Object.)
        request-exit   (or request-exit (Object.))
        read-eval-print
        (fn []
          (try
            (let [read-eval *read-eval*
                  input (clojure.main/with-read-known
                          (read request-prompt request-exit))]
             (or (#{request-prompt request-exit} input)
                 (let [value (binding [*read-eval* read-eval] (eval input))]
                   (print value)
                   (set! *3 *2)
                   (set! *2 *1)
                   (set! *1 value))))
           (catch Throwable e
             (caught e)
             (set! *e e))))]
    (with-bindings
     (try
      (init)
      (catch Throwable e
        (caught e)
        (set! *e e)))
     (prompt)
     (flush)
     (loop []
       (when-not 
       	 (try (identical? (read-eval-print) request-exit)
	  (catch Throwable e
	   (caught e)
	   (set! *e e)
	   nil))
         (when (need-prompt)
           (prompt)
           (flush))
         (recur))))))
(in-ns 'nightcode.repl)


(def  active-threads (atom nil))

;;Creates a repl that can be interrupted by using a sentinel atom.
;;Computations are evaluated in a future; if the eval is not complete
;;and the atom has reset to truthy, we cancel evaluation.  Otherwise
;;poll ever 50ms.
(defn ->interruptable [interrupted]
  (let [common-setters '#{ns in-ns set! load load-file}]
    (fn maybe-eval [arg]
      (if (and (coll? arg) (common-setters (first arg)))
        (eval arg)                 
        (let [binds (when (and (coll? arg)
                             (= (first arg) 'binding))
                      (second arg))              
              res  (if binds
                     (let [[_ binds & body] arg]
                       (eval `(binding [~@binds]
                                (future ~@body))))
                      (future (eval arg)))]
          (loop []
            (cond @interrupted
                  (do (future-cancel res)
                      (reset! interrupted nil)
                      (println "Thread Death: Evalution Interrupted!"))
                  (realized? res) @res
                  :else (do (Thread/sleep 100)
                            (recur)))))))))
;;API for creating our interruptable repl.
(defn repl [& {:keys [interrupt init request-exit]
               :or {request-exit :clj/exit}}]
  (binding [*ns* *ns*]
    (clojure.main/repl :eval (if interrupt (->interruptable interrupt)
                                 eval)
                       :init (fn []);(or init #())
                                        ;:caught repl-caught
                       :request-exit request-exit
                       )))

(defmacro echo-form [expr]
  (do (pprint/pprint expr)
      `~expr))

;;console handle [maybe bad to hold onto state...]
(def repl-console (atom nil))

;;programmatic repl api
(defn send-repl  [^nightcode.ui.JConsole console form]
  (.enterLine console (str form)))
(defn kill-repl  [console]       (send-repl console :clj/exit))
(defn clear-repl [^nightcode.ui.JConsole console]
  (.setText (.getTextArea console) "")
  (send-repl console ""))

(defn send-repl!  [form] (send-repl @repl-console form))
(defn print-repl!  [msg] 
  (.print @repl-console  (str msg)))
(defn println-repl!  [msg] 
  (.print @repl-console  (str msg "\n")))

(defn print-comments! [msg]
  (.print @repl-console  "\n")
  (println-repl! msg))

(defn kill-repl!  []     (kill-repl @repl-console))
(defn clear-repl! []     (clear-repl @repl-console))
(defn echo-repl!  [form]
  (let [input (str form)]
    (doto ^nightcode.ui.JConsole @repl-console
      (.print  (str input "\n"))
      (.enterLine input))))

(defn type-string [^nightcode.ui.JConsole
                   console input & {:keys [skip? speed]
                             :or {skip? (fn [] false)
                                  speed 64}}]
  (if (or (nil? speed) (zero? speed))
    (.print console (str input "\n"))
    (let [n (count input)]
      (loop [idx 0]
        (if (== idx n)
          (.print console "\n")
          (if-not (skip?)
            (do (.print console (nth input idx))
                (Thread/sleep speed)
                (recur (unchecked-inc idx)))
            (.print console (str (subs input idx) "\n"))))))))

(defn type-string! [input & opts]
  (apply type-string @repl-console opts))
                   
(defn type-repl!  [form & {:keys [speed] :or [speed 64]}]
  (let [input (str form)]
    (doto  @repl-console
      (type-string input :speed speed)
      (.enterLine input))))

;;send a series of forms to the repl...
;;comments will be elided...
(defn repl-tutorial! [forms & {:keys [speed] :or [speed 64]}]
  )

;;pulled from joinr/swingrepl fork
#_(defmacro eval-repl 
   "Convenience macro.  Allows us to evaluate arbitrary expressions in  
    the repl.  Provides the string conversion for us." 
   [rpl & body] 
  `(send-repl! ~rpl 
               ~(str (first body))))

(defn redirect-io
  [[in out] func]
  (binding [*out* out
            *err* out
            *in* in
            leiningen.core.main/*exit-process?* false]
    (func)))

;;may be unncessary...
(defn start-thread!*
  [in-out func]
  (->> (bound-fn []
         (try (func)
           (catch Exception e (some-> (.getMessage e) println))
           (finally (println "\n===" (utils/get-string :finished) "==="))))
       (redirect-io in-out)
       (bound-fn [])
       Thread.
       .start))

 
(defn create-pane
  "Returns the pane with the REPL."
  [console & {:keys [init interrupt]}]
  (let [_          (reset! repl-console console)
        start!     (fn []
                     #_(lein/start-thread! (ui/get-io! console)                                         
                                           (repl :interrupt interrupt :init init))
                     (start-thread!* (ui/get-io! console)
                                     (binding [*ns* *ns*]
                                          (bound-fn []
                                            (repl :interrupt interrupt :init init)))))
        interrupt  (or interrupt (atom nil))
        reset-repl (fn [& _]
                     (try (kill-repl console)
                          (catch Exception e nil))
                     (.setText (.getTextArea console) "")
                     (start!)
                     (s/request-focus! (-> console .getViewport .getView)))
        run!    (fn [& _]
                     (.setText (.getTextArea console) "")
                     (start!)
                     (s/request-focus! (-> console .getViewport .getView)))
        clear! (fn [& _]
                 (clear-repl console)
                 (s/request-focus! (-> console .getViewport .getView)))
        widget-bar (ui/wrap-panel :items
                    [(s/button :text "reset" :mnemonic \I
                               :listen [:action (fn [& _]
                                                  (reset-repl))])
                     (s/button :text "interrupt" :mnemonic \I
                               :listen [:action (fn [& _]
                                                  (reset! interrupt true))])
                     (s/button :text "clear repl"    :mnemonic \C
                               :listen [:action (fn [& _]
                                                  (clear!))])])
        repl-pane   (s/config! console :id :repl-console)
        pane  (doto (s/border-panel :center repl-pane)
                    (s/config! :north widget-bar))]
    (utils/set-accessible-name! (.getTextArea repl-pane #_pane) :repl-console)
    ; start the repl
    (run!)
    ; create a shortcut to restart the repl
    (when-not (sandbox/get-dir)
      (shortcuts/create-hints! pane)
      (shortcuts/create-mappings! pane {:repl-console run!}))
    ; return the repl pane
    pane))


(comment
(defn interrupt-test []
  (let [stop  (atom nil)
        _     (s/show! (s/frame :content
                         (s/button :text "interrupt!"
                                   :listen [:action (fn [& _]
                                                      (reset! stop true))])))]
      (repl :interrupt stop)))
  )
