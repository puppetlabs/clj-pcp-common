(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(defproject puppetlabs/cthun-message "0.0.1"
  :description "Message serialisation codec for cthun"
  :url "https://github.com/puppetlabs/clj-cthun-message"
  :license {:name ""
            :url ""}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]

                 [puppetlabs/kitchensink "1.1.0"]

                 [cheshire "5.4.0"]
                 [prismatic/schema "0.3.7"]

                 [clj-time "0.9.0"]

                 [com.taoensso/nippy "2.7.1"]

                 [org.clojars.smee/binary "0.3.0"]

                 ;; try+/throw+
                 [slingshot "0.12.2"]]

  :plugins [[lein-release "1.0.5"]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots"  "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]
  :lein-release {:scm :git, :deploy-via :lein-deploy})
