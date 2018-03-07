(defproject org-sync "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.julienxx/clj-slack "0.5.5"]
                 [org.clojure/tools.cli "0.3.5"]
                 [slingshot "0.12.2"]]
  :main org-sync.core
  :aot [org-sync.core]
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]]}}
  )
