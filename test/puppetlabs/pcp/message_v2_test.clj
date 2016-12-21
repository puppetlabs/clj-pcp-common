(ns puppetlabs.pcp.message-v2-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.message-v2 :refer :all]
            [slingshot.test]
            [schema.core :as s]
            [schema.test :as st]))

(deftest make-message-test
  (testing "the created message is a Message"
    (is (s/validate Message (make-message))))
  (testing "it makes a message"
    (is (= {:message_type ""}
           (dissoc (make-message) :id))))
  (testing "it makes a message with a map of parameters"
    (let [message (make-message {:sender "pcp://client01.example.com/test"
                                 :target "pcp:///server"})]
      (is (= {:message_type ""
              :sender "pcp://client01.example.com/test"
              :target "pcp:///server"}
             (dissoc message :id)))))
(testing "it makes a message with parameters"
    (let [message (make-message :sender "pcp://client01.example.com/test"
                                :target "pcp:///server")]
      (is (= {:message_type ""
              :sender "pcp://client01.example.com/test"
              :target "pcp:///server"}
             (dissoc message :id))))))

(deftest set-get-data-test
  (testing "it gets and sets the data frame"
    (let [message (set-data (make-message) {:some "data"})]
      (is (= (get-data message)
             {:some "data"})))))

(deftest encode-test
  (with-redefs [puppetlabs.kitchensink.core/uuid (constantly "b9835b34-52d0-4f81-b026-a3941333e082")]
    (testing "it encodes a message"
      (is (= "{\"id\":\"b9835b34-52d0-4f81-b026-a3941333e082\",\"message_type\":\"\",\"data\":[1,2,3]}"
             (encode (set-data (make-message) [1 2 3])))))))

(deftest decode-test
  (testing "data is accessible"
    (is (= [1 2 3]
           (get-data (decode "{\"data\":[1,2,3],\"id\":\"b9835b34-52d0-4f81-b026-a3941333e082\",\"message_type\":\"\"}"))))))

(deftest encoder-roundtrip-test
  (testing "it can roundtrip messages"
    (let [message (set-data (make-message {:sender "pcp://client01.example.com/test"}) "results")]
      (is (= message
             (decode (encode message)))))))
