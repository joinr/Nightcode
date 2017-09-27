(ns nightcode.window
  (:require [nightcode.dialogs :as dialogs]
            [nightcode.editors :as editors]
            [nightcode.file-browser :as file-browser]
            [nightcode.git :as git]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.ui :as ui]
            [seesaw.core :as s]
            [seesaw.icon :as i]
            [sanersubstance.core :as substance])
  (:import [java.awt Window]
           [java.awt.event WindowAdapter]
           [java.lang.reflect InvocationHandler Proxy]
           [org.pushingpixels.substance.api SubstanceLookAndFeel]
           [org.pushingpixels.substance.api.skin GraphiteSkin]))

;;https://stackoverflow.com/questions/36128291/how-to-make-a-swing-application-have-dark-nimbus-theme-netbeans
(defn set-nimbus! []
  (do  (javax.swing.UIManager/put "control", (java.awt.Color.  128, 128, 128) );
       (javax.swing.UIManager/put "info", (java.awt.Color. 128,128,128) );
       (javax.swing.UIManager/put "nimbusBase", (java.awt.Color.  18, 30, 49) );
       (javax.swing.UIManager/put "nimbusAlertYellow", (java.awt.Color.  248, 187, 0) );
       (javax.swing.UIManager/put "nimbusDisabledText", (java.awt.Color.  128, 128, 128) );
       (javax.swing.UIManager/put "nimbusFocus", (java.awt.Color. 115,164,209) );
       (javax.swing.UIManager/put "nimbusGreen", (java.awt.Color. 176,179,50) );
       (javax.swing.UIManager/put "nimbusInfoBlue", (java.awt.Color.  66, 139, 221) );
       (javax.swing.UIManager/put "nimbusLightBackground", (java.awt.Color.  18, 30, 49) );
       (javax.swing.UIManager/put "nimbusOrange", (java.awt.Color. 191,98,4) );
       (javax.swing.UIManager/put "nimbusRed", (java.awt.Color. 169,46,34) );
       (javax.swing.UIManager/put "nimbusSelectedText", (java.awt.Color.  255, 255, 255) );
       (javax.swing.UIManager/put "nimbusSelectionBackground", (java.awt.Color.  104, 93, 156) );
       (javax.swing.UIManager/put "text", (java.awt.Color.  230, 230, 230))
     (doseq [info (javax.swing.UIManager/getInstalledLookAndFeels)]
       (if (= "Nimbus" (.getName info))
         (javax.swing.UIManager/setLookAndFeel (.getClassName info))))))

(defn set-theme!
  "Sets the theme based on the command line arguments."
  [args]
  (s/native!)
  #_(set-nimbus!)
  (let [{:keys [shade skin-object theme-resource]} args]
    (when theme-resource (reset! ui/theme-resource theme-resource))
    (SubstanceLookAndFeel/setSkin (or skin-object (GraphiteSkin.)))
    (substance/enforce-event-dispatch)))

(defn show-shut-down-dialog!
  "Displays a dialog confirming whether the program should shut down."
  []
  (dialogs/show-shut-down-dialog! (editors/unsaved-paths)))

(defn confirm-exit-app!
  "Shuts down unless a quit handler exists or the user cancels it."
  []
  (if (and (nil? (try (Class/forName "com.apple.eawt.QuitHandler")
                   (catch Exception _)))
           (show-shut-down-dialog!))
    (System/exit 0)
    true))

(defn set-icon!
  "Sets the dock icon on OS X."
  [path]
  (some-> (try (Class/forName "com.apple.eawt.Application")
            (catch Exception _))
          (.getMethod "getApplication" (into-array Class []))
          (.invoke nil (object-array []))
          (.setDockIconImage (.getImage (i/icon path)))))

(defn enable-full-screen!
  "Enables full screen mode on OS X."
  [window]
  (some-> (try (Class/forName "com.apple.eawt.FullScreenUtilities")
            (catch Exception _))
          (.getMethod "setWindowCanFullScreen"
            (into-array Class [Window Boolean/TYPE]))
          (.invoke nil (object-array [window true]))))

(defn create-quit-handler
  "Creates an OS X quit handler."
  []
  (when-let [quit-class (try (Class/forName "com.apple.eawt.QuitHandler")
                          (catch Exception _))]
    (Proxy/newProxyInstance (.getClassLoader quit-class)
                            (into-array Class [quit-class])
                            (reify InvocationHandler
                              (invoke [this proxy method args]
                                (when (= (.getName method)
                                         "handleQuitRequestWith")
                                  (if (show-shut-down-dialog!)
                                    (.performQuit (second args))
                                    (.cancelQuit (second args)))))))))

(defn override-quit-handler!
  "Overrides the default quit handler on OS X."
  []
  (when-let [quit-handler (create-quit-handler)]
    (some-> (try (Class/forName "com.apple.eawt.Application")
              (catch Exception _))
            (.getMethod "getApplication" (into-array Class []))
            (.invoke nil (object-array []))
            (.setQuitHandler quit-handler))))

(defn add-listener!
  "Sets callbacks for window events."
  [window]
  (override-quit-handler!)
  (.addWindowListener window
    (proxy [WindowAdapter] []
      (windowActivated [e]
        ; force hints to hide
        (reset! shortcuts/down? false)
        (shortcuts/toggle-hint! @editors/tabs false)
        (shortcuts/toggle-hints! @ui/root false)
        ; update the project tree and various panes
        (ui/update-project-tree!)
        (file-browser/update-card!)
        (git/update-sidebar!))
      (windowClosing [e]
        (when (show-shut-down-dialog!)
          (System/exit 0))))))
