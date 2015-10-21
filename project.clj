(defproject puppetlabs/pcp-common "0.5.0"
  :description "Common protocol components for PCP"
  :url "https://github.com/puppetlabs/clj-pcp-common"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]

                 [puppetlabs/kitchensink "1.1.0"]

                 [cheshire "5.5.0"]
                 [prismatic/schema "0.4.3"]

                 [clj-time "0.9.0"]

                 [com.taoensso/nippy "2.9.0"]

                 [org.clojars.smee/binary "0.3.0"]

                 ;; try+/throw+
                 [slingshot "0.12.2"]]

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]]

  :profiles {:cljfmt {:plugins [[lein-cljfmt "0.3.0"]
                                [lein-parent "0.2.1"]]
                      :parent-project {:path "../pl-clojure-style/project.clj"
                                       :inherit [:cljfmt]}}}

  :aliases {"cljfmt" ["with-profile" "+cljfmt" "cljfmt"]}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :lein-release {:scm :git
                 :deploy-via :lein-deploy})
