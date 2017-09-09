(defproject nightcode "2.3.8-SNAPSHOT"
  :description "An IDE for Clojure and Java"
  :url "https://github.com/oakes/Nightcode"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :source-paths #{"src/clj" "src/cljs"}
  :resource-paths #{"resources"}
  :dependencies [[org.clojure/test.check "0.9.0" :scope "test"]
                 [adzerk/boot-cljs "1.7.228-2" :scope "test"]
                                        ; cljs deps
                 [org.clojure/clojurescript "1.9.473" :scope "test"]
                 [paren-soup "2.8.13" :scope "test"]
                 [mistakes-were-made "1.7.3" :scope "test"]
                 [cljsjs/codemirror "5.24.0-1" :scope "test"]
                                        ; clj deps
                 [org.clojure/clojure "1.9.0-alpha17"]
                 [leiningen "2.7.1" :exclusions [leiningen.search]]
                 [ring "1.6.1"]
                 [play-cljs/lein-template "0.10.1-5"]
                 [eval-soup "1.2.2" :exclusions [org.clojure/core.async]]
                 [org.eclipse.jgit/org.eclipse.jgit "4.6.0.201612231935-r"]]
  :aot [clojure.main nightcode.core nightcode.lein]
  ;:main ^:skip-aot nightcode.Nightcode
  ;:include #{#"\.jar$"}
  :main nightcode.core
  ;;:manifest {"Description" "An IDE for Clojure and ClojureScript"
  ;;            "Url" "https://github.com/oakes/Nightcode"}
  )
