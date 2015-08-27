(ns puppetlabs.cthun.protocol-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.protocol :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]))

(deftest uri-schema-test
  (testing "valid uris"
    (is (= (s/validate Uri "cth:///server") "cth:///server"))
    (is (= (s/validate Uri "cth://bananas/server") "cth://bananas/server"))
    (is (= (s/validate Uri "cth://shoes/test") "cth://shoes/test")))
  (testing "invalid uris"
    (is (thrown? Exception (s/validate Uri "cth://server")))
    (is (thrown? Exception (s/validate Uri "server")))
    (is (thrown? Exception (s/validate Uri "cth://test/with/too_many_slashes")))))

(deftest uuid?-test
  (is (uuid? (ks/uuid)))
  (is (not (uuid? "let me tell you a story"))))

(deftest explode-uri-test
  (testing "It raises on invalid uris"
    (is (thrown? Exception (explode-uri ""))))
  (testing "It returns component chunks"
    (is (= [ "localhost" "agent"] (explode-uri "cth://localhost/agent")))
    (is (= [ "localhost" "*" ] (explode-uri "cth://localhost/*")))
    (is (= [ "*" "agent" ] (explode-uri "cth://*/agent")))))

(deftest uri-wildcard?-test
  (is (= true  (uri-wildcard? "cth://*/agent")))
  (is (= true  (uri-wildcard? "cth://agent01.example.com/*")))
  (is (= false (uri-wildcard? "cth://agent01.example.com/agent"))))
