(defproject joinr/nightcode "1.3.4-SNAPSHOT"
  :description "An IDE for Clojure and Java"
  :url "https://github.com/oakes/Nightcode"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[com.fifesoft/autocomplete "3.0.0"]
                 [com.fifesoft/rsyntaxtextarea "3.0.3"]
                 [com.github.insubstantial/substance "7.3"]
                 [compliment "0.3.8"]
                 [gwt-plugin "0.1.6"]
                 [hiccup "1.0.5"]
                 ;;note: we have to use < 2.8.1 due
                 ;;to some silliness with lein-droid.
                 ;;For minimal changes, I'm okay with this.
                 [leiningen  "2.9.1"]
                  ;:exclusions [leiningen.search org.clojure/data.xml]]
                 #_[lein-ancient "0.6.15"
                  :exclusions [clj-aws-s3]]
                 [lein-cljsbuild "1.1.7"]
                 [lein-clr "0.2.2"]
                 [joinr/lein-droid "0.4.7-SNAPSHOT"]
                 [lein-typed "0.4.6"]
                 [lein-ring "0.12.5"]
                 [mistakes-were-made "1.8.0"]
                 [net.java.balloontip/balloontip "1.2.4.1"]
                 [org.clojure/clojure "1.10.1"]
                 ;[org.clojure/core.incubator "0.1.3"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.eclipse.jgit "3.5.3.201412180710-r"
                  :exclusions [org.apache.httpcomponents/httpclient]]
                 [org.flatland/ordered "1.5.7"]
                 [play-clj/lein-template "1.1.1"]
                 [seesaw "1.5.0"]
                 [cross-parinfer "1.5.0"]
                 [sanersubstance "0.1.0-SNAPSHOT"]
                 [joinr/nightcode-java "0.1.0-SNAPSHOT"]
                 ;;satisfy the build gods.
                 [commons-codec/commons-codec "1.12"]
                 ;;forgot we added a dependency here
                 [org.clojure/core.async "0.4.490"]
                 #_[org.clojure/data.xml "0.0.8"]]
  :uberjar-exclusions [#"PHPTokenMaker\.class"
                       #"org\/apache\/lucene"]
  :resource-paths ["resources"]
  :source-paths ["src/clojure"]
  :aot [clojure.main nightcode.core nightcode.lein]
  ;:main ^:skip-aot nightcode.Nightcode
  )
