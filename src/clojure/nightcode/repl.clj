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

;;Creates a repl that can be interrupted by using a sentinel atom.
;;Computations are evaluated in a future; if the eval is not complete
;;and the atom has reset to truthy, we cancel evaluation.  Otherwise
;;poll ever 50ms.
(defn ->interruptable [interrupted]
  (fn maybe-eval [arg]
    (let [res (future (eval arg))]
      (loop []
        (cond @interrupted
                (do (future-cancel res)
                    (reset! interrupted nil)
                    (println "Thread Death: Evalution Interrupted!"))
                (realized? res) @res
              :else (do (Thread/sleep 50)
                        (recur)))))))

;;API for creating our interruptable repl.
(defn repl [& {:keys [interrupt init request-exit]
               :or {request-exit :clj/exit}}]
  (clojure.main/repl :eval (if interrupt (->interruptable interrupt)
                               eval)
                     :init (fn []);(or init #())
                     ;:caught repl-caught
                     :request-exit request-exit
                     ))

(defmacro echo-form [expr]
  (do (pprint/pprint expr)
      `~expr))  

;;console handle [maybe bad to hold onto state...]
(def repl-console (atom nil))

;;programmatic repl api
(defn send-repl [console form]  (.enterLine console (str form)))
(defn kill-repl [console]       (send-repl console :clj/exit))
(defn clear-repl [console]
  (.setText (.getTextArea console) "")
  (send-repl console ""))

(defn send-repl!  [form] (send-repl @repl-console form))
(defn kill-repl!  []     (kill-repl @repl-console))
(defn clear-repl! []     (clear-repl @repl-console))
 

(defn create-pane
  "Returns the pane with the REPL."
  [console & {:keys [init interrupt]}]
  (let [_          (reset! repl-console console)
        start!     (fn []
                     (lein/start-thread! (ui/get-io! console)
                                         (binding [*ns* *ns*]
                                           (repl :interrupt interrupt :init init))))
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
