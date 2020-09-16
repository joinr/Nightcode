(ns nightcode.utils
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.parse :as parse]
            [clojure.data.xml :as xml])
  (:import [java.io File]
           [java.math BigInteger]
           [java.net URL]
           [java.nio.file Files Path Paths]
           [java.util Locale]
           [java.util.jar Manifest]
           [java.util.prefs Preferences]
           [java.security MessageDigest]
           [javax.swing.tree TreePath]
           [java.text SimpleDateFormat]
           [org.fife.ui.rsyntaxtextarea SyntaxConstants]))

; preferences

(def ^Preferences prefs (.node (Preferences/userRoot) "nightcode"))

(defn write-pref!
  "Writes a key-value pair to the preference file."
  [k v]
  (io! (doto prefs
         (.put (name k) (pr-str v))
         .flush)))

(defn remove-pref!
  "Removes a key-value pair from the preference file."
  [k]
  (io! (doto prefs
         (.remove (name k))
         .flush)))

(defn read-pref
  "Reads value from the given key in the preference file."
  [k & [default-val]]
  (if-let [string (.get prefs (name k) nil)]
    (edn/read-string string)
    default-val))

; language

(def lang-files {"en" "values/strings.xml"})
(def lang-strings (-> (get lang-files (.getLanguage (Locale/getDefault)))
                      (or (get lang-files "en"))
                      io/resource
                      .toString
                      xml/parse
                      :content))

(defn get-string
  "Returns the localized string for the given keyword."
  [res-name]
  (-> #(= (get-in % [:attrs :name]) (-> res-name name (string/replace "-" "_")))
      (filter lang-strings)
      first
      :content
      first
      (or (name res-name))
      (clojure.string/replace "\\" "")))

(defn set-accessible-name!
  [widget name]
  (-> widget
      .getAccessibleContext
      (.setAccessibleName (if (keyword? name) (get-string name) name))))

; paths and encodings

(def ^:const clojure-exts #{"boot" "clj" "cljc" "cljs" "cljx" "edn" "pxi"})
(def ^:const wrap-exts #{"md" "txt"})
(def styles {"as"         SyntaxConstants/SYNTAX_STYLE_ACTIONSCRIPT
             "asm"        SyntaxConstants/SYNTAX_STYLE_ASSEMBLER_X86
             "bat"        SyntaxConstants/SYNTAX_STYLE_WINDOWS_BATCH
             "boot"       SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "c"          SyntaxConstants/SYNTAX_STYLE_C
             "cc"         SyntaxConstants/SYNTAX_STYLE_C
             "cl"         SyntaxConstants/SYNTAX_STYLE_LISP
             "cpp"        SyntaxConstants/SYNTAX_STYLE_CPLUSPLUS
             "css"        SyntaxConstants/SYNTAX_STYLE_CSS
             "clj"        SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "cljc"       SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "cljs"       SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "cljx"       SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "cs"         SyntaxConstants/SYNTAX_STYLE_CSHARP
             "dtd"        SyntaxConstants/SYNTAX_STYLE_DTD
             "edn"        SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "groovy"     SyntaxConstants/SYNTAX_STYLE_GROOVY
             "h"          SyntaxConstants/SYNTAX_STYLE_C
             "hpp"        SyntaxConstants/SYNTAX_STYLE_CPLUSPLUS
             "htm"        SyntaxConstants/SYNTAX_STYLE_HTML
             "html"       SyntaxConstants/SYNTAX_STYLE_HTML
             "java"       SyntaxConstants/SYNTAX_STYLE_JAVA
             "js"         SyntaxConstants/SYNTAX_STYLE_JAVASCRIPT
             "json"       SyntaxConstants/SYNTAX_STYLE_JAVASCRIPT
             "jsp"        SyntaxConstants/SYNTAX_STYLE_JSP
             "jspx"       SyntaxConstants/SYNTAX_STYLE_JSP
             "lisp"       SyntaxConstants/SYNTAX_STYLE_LISP
             "lua"        SyntaxConstants/SYNTAX_STYLE_LUA
             "makefile"   SyntaxConstants/SYNTAX_STYLE_MAKEFILE
             "markdown"   SyntaxConstants/SYNTAX_STYLE_NONE
             "md"         SyntaxConstants/SYNTAX_STYLE_NONE
             "mustache"   SyntaxConstants/SYNTAX_STYLE_NONE
             "pas"        SyntaxConstants/SYNTAX_STYLE_DELPHI
             "properties" SyntaxConstants/SYNTAX_STYLE_PROPERTIES_FILE
             "php"        SyntaxConstants/SYNTAX_STYLE_PHP
             "pl"         SyntaxConstants/SYNTAX_STYLE_PERL
             "pm"         SyntaxConstants/SYNTAX_STYLE_PERL
             "pxi"        SyntaxConstants/SYNTAX_STYLE_CLOJURE
             "py"         SyntaxConstants/SYNTAX_STYLE_PYTHON
             "rb"         SyntaxConstants/SYNTAX_STYLE_RUBY
             "s"          SyntaxConstants/SYNTAX_STYLE_ASSEMBLER_X86
             "sbt"        SyntaxConstants/SYNTAX_STYLE_SCALA
             "scala"      SyntaxConstants/SYNTAX_STYLE_SCALA
             "sh"         SyntaxConstants/SYNTAX_STYLE_UNIX_SHELL
             "sql"        SyntaxConstants/SYNTAX_STYLE_SQL
             "tcl"        SyntaxConstants/SYNTAX_STYLE_TCL
             "tex"        SyntaxConstants/SYNTAX_STYLE_LATEX
             "txt"        SyntaxConstants/SYNTAX_STYLE_NONE
             "xhtml"      SyntaxConstants/SYNTAX_STYLE_XML
             "xml"        SyntaxConstants/SYNTAX_STYLE_XML})

(defn get-extension
  "Returns the extension in the given path name."
  [path]
  (->> (.lastIndexOf path ".")
       (+ 1)
       (subs path)
       clojure.string/lower-case))

(defn text-file?
  "Returns true if the file is of type text, false otherwise."
  [^File file]
  (-> (Files/probeContentType ^Path (.toPath file))
      (or "")
      (.startsWith "text")))

(defn valid-file?
  "Returns true if the file can be read by the text editor."
  [f]
  (and (.isFile f)
       (or (contains? styles (get-extension (.getCanonicalPath f)))
           (text-file? f))))

(defn get-unsaved-paths-message
  "Returns a message indicating which paths are currently unsaved."
  [unsaved-paths]
  (when (seq unsaved-paths)
    (str (get-string :unsaved-changes)
         \newline \newline
         (->> unsaved-paths
              (map #(.getName (io/file %)))
              (clojure.string/join \newline))
         \newline \newline)))

(defn get-exec-uri
  "Returns the executable as a java.net.URI."
  [class-name]
  (-> (Class/forName class-name)
      .getProtectionDomain
      .getCodeSource
      .getLocation
      .toURI))

(defn get-project
  "Returns the project.clj file as a list."
  [class-name]
  (when (.isFile (io/file (get-exec-uri class-name)))
    (->> (io/resource "project.clj")
         slurp
         read-string
         (binding [*read-eval* false]))))

(defn tree-path-to-str
  "Gets the string path for the given JTree path object."
  [^TreePath tree-path]
  (try
    (some-> tree-path
            .getLastPathComponent
            .getUserObject
            :file
            .getCanonicalPath)
    (catch Exception _)))

(defn get-relative-path
  "Returns the selected path as a relative URI to the project path."
  [project-path selected-path]
  (-> (.toURI (io/file project-path))
      (.relativize (.toURI (io/file selected-path)))
      (.getPath)))

(defn get-relative-dir
  "Returns the selected directory as a relative URI to the project path."
  [project-path selected-path]
  (let [selected-dir (if (.isDirectory (io/file selected-path))
                       selected-path
                       (-> (io/file selected-path)
                           .getParentFile
                           .getCanonicalPath))]
    (get-relative-path project-path selected-dir)))

(defn delete-parents-recursively!
  "Deletes the given file along with all empty parents unless they are in
project-set."
  [project-set path]
  (let [f (io/file path)]
    (when (and (zero? (count (.listFiles f)))
               (not (contains? project-set path)))
      (io/delete-file f true)
      (->> f
           .getParentFile
           .getCanonicalPath
           (delete-parents-recursively! project-set))))
  nil)

(defn delete-children-recursively!
  "Deletes the children of the given dir along with the dir itself."
  [path]
  (let [f (io/file path)]
    (when (.isDirectory f)
      (doseq [f2 (.listFiles f)]
        (delete-children-recursively! f2)))
    (io/delete-file f))
  nil)

(defn format-project-name
  "Formats the given string as a valid project name."
  [name-str]
  (-> name-str
      clojure.string/lower-case
      (clojure.string/replace "_" "-")
      (clojure.string/replace #"[^a-z0-9-.]" "")))

(defn format-package-name
  "Formats the given string as a valid package name."
  [name-str]
  (-> name-str
      clojure.string/lower-case
      (clojure.string/replace "-" "_")
      (clojure.string/replace #"[^a-z0-9_.]" "")))

(defn project-path?
  "Determines if the given path contains a project.clj file."
  [^String path]
  (and path
       (.isDirectory (io/file path))
       (.exists (io/file path "project.clj"))))

(defn parent-path?
  "Determines if the given parent path is equal to or a parent of the child."
  [^String parent-path ^String child-path]
  (or (= parent-path child-path)
      (and parent-path
           child-path
           (.isDirectory (io/file parent-path))
           (.startsWith child-path (str parent-path File/separator)))))

(defn uri->str
  "Converts a java.net.URI to a String."
  [uri]
  (-> uri Paths/get .normalize .toString))

(defn get-ns-from-path
  "Returns the namespace and dependencies in the given file."
  [path]
  (let [form (-> path io/file file/read-file-ns-decl)]
    {:ns (second form)
     :deps (parse/deps-from-ns-decl form)}))

(defn sort-by-dependency
  "Sorts the paths from least to most dependent."
  [paths]
  (let [deps (->> paths
                  (map #(vector % (get-ns-from-path %)))
                  (into {}))]
    (sort #(cond
             (contains? (get-in deps [%1 :deps]) (get-in deps [%2 :ns])) 1
             (contains? (get-in deps [%2 :deps]) (get-in deps [%1 :ns])) -1
             :else 0)
          paths)))

(defn ^:private create-hash
  [data-barray]
  (.digest (MessageDigest/getInstance "SHA1") data-barray))

(defn hashed-keyword
  "Returns the SHA1 hash of the given string as a keyword."
  [s]
  (->> (.getBytes s "UTF-8") create-hash BigInteger. (format "%x") keyword))

(defn format-date
  "Formats unix time (in seconds) into a readable date."
  [unix-time]
  (.format (SimpleDateFormat. "yyyy.MM.dd HH:mm:ss") (* 1000 unix-time)))
