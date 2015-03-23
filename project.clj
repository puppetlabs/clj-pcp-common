(defproject puppetlabs/cthun-message "0.0.1-SNAPSHOT"
  :description "Message serialisation codec for cthun"
  :url "https://github.com/puppetlabs/clj-cthun-message"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]

                 [puppetlabs/kitchensink "1.1.0"]

                 [cheshire "5.4.0"]
                 [prismatic/schema "0.3.7"]

                 [com.taoensso/nippy "2.7.1"]

                 [org.clojars.smee/binary "0.3.0"]

                 ;; try+/throw+
                 [slingshot "0.12.2"]])
