(defproject puppetlabs/pcp-common "1.1.2"
  :description "Common protocol components for PCP"
  :url "https://github.com/puppetlabs/clj-pcp-common"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :min-lein-version "2.7.1"

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :parent-project {:coords [puppetlabs/clj-parent "0.4.1"]
                   :inherit [:managed-dependencies]}

  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]

                 [puppetlabs/kitchensink]

                 [cheshire]
                 [prismatic/schema]

                 [com.taoensso/nippy "2.9.0"]

                 [org.clojars.smee/binary "0.3.0"]

                 ;; try+/throw+
                 [slingshot]

                 ;; remove version when moving to clj-parent 0.5+
                 [puppetlabs/i18n "0.7.0"]]

  :plugins [[lein-parent "0.3.1"]
            [lein-release "1.0.5" :exclusions [org.clojure/clojure]]
            [puppetlabs/i18n "0.7.0"]]

  :profiles {:cljfmt {:plugins [[lein-cljfmt "0.3.0"]
                                [lein-parent "0.3.1"]]
                      :parent-project {:path "../pl-clojure-style/project.clj"
                                       :inherit [:cljfmt]}}
             :test-base {:test-paths ["test"]}
             :test-schema-validation [:test-base
                                      {:injections [(do
                                                      (require 'schema.core)
                                                      (schema.core/set-fn-validation! true))]}]}

  :aliases {"cljfmt" ["with-profile" "+cljfmt" "cljfmt"]
            "test-all" ["with-profile" "test-base:test-schema-validation" "test"]}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :lein-release {:scm :git
                 :deploy-via :lein-deploy})
