(ns puppetlabs.cthun.message-test
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [puppetlabs.cthun.message :refer :all]
            [slingshot.slingshot :refer [try+]]))

(deftest make-message-test
  (testing "it makes a message"
    (is (= (assoc (make-message) :_data_frame [])
           {:id ""
            :sender ""
            :endpoints []
            :expires "1970-01-01T00:00:00.000Z"
            :data_schema ""
            :_hops []
            :_data_frame []
            :_data_flags #{}
            :_destination ""}))))

(deftest set-expiry-test
  (testing "it sets expiries to what you tell it"
    (is (= (:expiry (set-expiry (make-message) "1971-01-01T00:00:00.000Z") "1971-01-01T00:00:00.000Z"))))
  (testing "it supports relative time"
    ;; Hello future test debugger.  At one point someone said "we
    ;; should never be 3 seconds before the epoch".  Past test writer
    ;; needs a slap.
    (is (not (= (:expiry (set-expiry (make-message) 3 :seconds)) "1970-01-01T00:00:00.000Z")))))

(deftest add-hop-test
  (with-redefs [puppetlabs.kitchensink.core/timestamp (fn [] "Tomorrow")]
    (testing "it adds a hop"
      (is (= [{:server "cth://fake/server"
                       :time   "Another day"
                       :stage  "potato"}]
             (:_hops (add-hop (make-message) "potato" "Another day")))))
    (testing "it allows timestamp to be optional"
      (is (= [{:server "cth://fake/server"
               :time   "Tomorrow"
               :stage  "potato"}]
             (:_hops (add-hop (make-message) "potato")))))
    (testing "it adds hops in the expected order"
      (is (= [{:server "cth://fake/server"
               :time   "Tomorrow"
               :stage  "potato"}
              {:server "cth://fake/server"
               :time   "Tomorrow"
               :stage  "mash"}]
             (:_hops (-> (make-message)
                         (add-hop "potato")
                         (add-hop "mash"))))))))

(deftest get-json-data-test
  (testing "it json decodes the data frame"
    (let [message (assoc (make-message) :_data_frame (string->bytes "{}"))]
      (is (= (get-json-data message) {})))))

(deftest set-json-data-test
  (testing "it json encodes to the data frame"
    (let [message (set-json-data (make-message) {})]
      (is (= (bytes->string (:_data_frame message))
             "{}")))))

(deftest encode-descriptor-test
  (testing "it encodes"
    (is (= 1
           (encode-descriptor {:type 1})))
    (is (= 2r10000001
           (encode-descriptor {:type 1 :flags #{:unused1}})))
    (is (= 2r10010001
           (encode-descriptor {:type 1 :flags #{:unused1 :unused4}})))))

(deftest decode-descriptor-test
  (testing "it decodes"
    (is (= {:type 1 :flags #{}}
           (decode-descriptor 1)))
    (is (= {:type 1 :flags #{:unused1}}
           (decode-descriptor 2r10000001)))
    (is (= {:type 1 :flags #{:unused1 :unused4}}
           (decode-descriptor 2r10010001)))))

(deftest encode-test
  (testing "when being strict, we take a Message only"
    (s/with-fn-validation
      (try+
       (encode {})
       (catch [:type :schema.core/error] m
         (is true "Rejected an empty map as a Message"))
       (else (is (not true) "Expected exception to be raised when passed an empty map")))))
  (testing "it returns a byte array"
    ;; subsequent tests will use vec to ignore this
    (is (= (class (encode {}))
           (class (byte-array 0)))))
  (testing "it encodes a message"
    (is (= (vec (encode {}))
           [1,
            1, 0 0 0 2, 123 125,
            2, 0 0 0 0])))
  (testing "it adds :hops as an optional final chunk"
    (is (= (vec (encode {:_hops "some"}))
           [1,
            1, 0 0 0 2, 123 125,
            2, 0 0 0 0,
            3, 0 0 0 15, 123 34 104 111 112 115 34 58 34 115 111 109 101 34 125])))
  (testing "it encodes the data chunk"
    (is (= (vec (encode (set-data {} (byte-array (map byte "haha")))))
           [1,
            1, 0 0 0 2, 123 125,
            2, 0 0 0 4, 104 97 104 97]))))

(deftest decode-test
  (with-redefs [schema.core/validate (fn [s d] d)]
    (testing "it only handles version 1 messages"
      (try+
       (decode (byte-array [2]))
       (is (not true) "Expected exception to be raised for non type-1 message")
       (catch map? m
         (is (= :puppetlabs.cthun.message/message-malformed (:type m))))
       (catch Object _
         (is (not true) "Unexpected exception"))))
    (testing "it insists on envelope chunk first"
      (try+
       (decode (byte-array [1,
                            2, 0 0 0 2, 123 125]))
       (is (not true) "Expected exception to be thrown by decode when first chunk is not type 1")
       (catch map? m
         (is (= :puppetlabs.cthun.message/message-invalid (:type m))))
       (catch Object _
         (is (not true) "Unexpected exception"))))
    (testing "it decodes the null message"
      (is (= (filter-private (decode (byte-array [1, 1, 0 0 0 2, 123 125])))
             (filter-private (make-message)))))
    (testing "it insists on a well-formed envelope"
      (try+
       (decode (byte-array [1,
                            1, 0 0 0 1, 123]))
       (is (not true) "Expected exception to be thrown by decode when envelope is invalid")
       (catch map? m
         (is (= :puppetlabs.cthun.message/envelope-malformed (:type m))))
       (catch Object _
         (is (not true) "Unexpected exception"))))
    (testing "it insists on a complete envelope"
      (with-redefs [schema.core/validate (fn [s d] (throw (Exception. "oh dear")))]
        (try+
         (decode (byte-array [1,
                              1, 0 0 0 2, 123 125]))
         (is (not true) "Expected exception to be thrown by decode when envelope is invalid")
         (catch map? m
           (is (= :puppetlabs.cthun.message/envelope-invalid (:type m))))
         (catch Object _
           (is (not true) "Unexpected exception")))))
    (testing "data is accessible"
      (let [message (decode (byte-array [1,
                                         1, 0 0 0 2, 123 125,
                                         2, 0 0 0 3, 108 111 108]))]
        (is (= (String. (get-data message)) "lol"))))))

(deftest encoder-roundtrip-test
  (with-redefs [schema.core/validate (fn [s d] d)]
    (testing "it can roundtrip data"
      (let [data (byte-array (map byte "hola"))
            encoded (encode (set-data {} data))
            decoded (decode encoded)]
        (is (= (vec (get-data decoded))
               (vec data)))))))
