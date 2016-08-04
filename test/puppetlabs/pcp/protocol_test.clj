(ns puppetlabs.pcp.protocol-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.protocol :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [schema.test :as st]))

(deftest uri-schema-test
  (testing "valid uris"
    (is (= (s/validate Uri "pcp:///server") "pcp:///server"))
    (is (= (s/validate Uri "pcp://bananas/server") "pcp://bananas/server"))
    (is (= (s/validate Uri "pcp://shoes/test") "pcp://shoes/test")))
  (testing "invalid uris"
    (is (thrown? Exception (s/validate Uri "")))
    (is (thrown? Exception (s/validate Uri "pcp://server")))
    (is (thrown? Exception (s/validate Uri "server")))
    (is (thrown? Exception (s/validate Uri "pcp://test/with/too_many_slashes")))))

(deftest uuid?-test
  (is (uuid? (ks/uuid)))
  (is (not (uuid? "let me tell you a story"))))

(deftest explode-uri-test
  (testing "It raises on invalid uris"
    (is (thrown? Exception (explode-uri ""))))
  (testing "It returns component chunks"
    (is (= ["localhost" "agent"] (explode-uri "pcp://localhost/agent")))
    (is (= ["localhost" "*"] (explode-uri "pcp://localhost/*")))
    (is (= ["*" "agent"] (explode-uri "pcp://*/agent")))))

(deftest uri-wildcard?-test
  (is (= true  (uri-wildcard? "pcp://*/agent")))
  (is (= true  (uri-wildcard? "pcp://agent01.example.com/*")))
  (is (= false (uri-wildcard? "pcp://agent01.example.com/agent"))))
