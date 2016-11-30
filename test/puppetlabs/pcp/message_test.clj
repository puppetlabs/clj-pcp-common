(ns puppetlabs.pcp.message-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.message :refer :all]
            [slingshot.test]
            [schema.core :as s]
            [schema.test :as st]))

(deftest byte-array-conversion-test
  (testing "byte array round trip conversion"
    (is (= "☃" (-> "☃" string->bytes bytes->string)))))

(deftest make-message-test
  (testing "it makes a message with parameters"
    (let [message (make-versioned-message
                    "v2.0"
                    {:sender "pcp://client01.example.com/test"
                     :target ["pcp:///server"]})]
      (is (s/validate Message message))
      (is (= {:sender "pcp://client01.example.com/test"
              :target ["pcp:///server"]
              :message_type ""}
             (dissoc message :id))))))

(deftest get-data-test
  (testing "it returns data from the data frame"
    (let [message (set-data (make-message) (byte-array [4 6 2]))]
      (is (= [4 6 2]
             (vec (get-data message)))))))

(deftest set-data-test
  (testing "it sets the data frame"
    (let [message (set-data (make-message) (byte-array [1 2 3]))]
      (is (= (vec (get-data message))
             [1 2 3])))))

(deftest get-json-data-test
  (testing "it json decodes the data frame"
    (let [message (set-data (make-versioned-message "v1" {}) (string->bytes "{}"))]
      (is (= (get-json-data message "v1.0") {})))))

(deftest set-json-data-test
  (testing "it json encodes to the data frame"
    (let [message (set-json-data (make-message) {})]
      (is (= (bytes->string (get-data message))
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
    (is (thrown+? [:type :schema.core/error]
                  (encode {}))
        "Rejected an empty map as a Message"))
  ;; don't include the envelope in the encoded messages in the following
  ;; tests for the sake of brevity
  (with-redefs [message->envelope (constantly {})]
    (testing "it returns a byte array"
      ;; subsequent tests will use vec to ignore this
      (let [message (-> (make-versioned-message "v1.0" {})
                        (set-data (byte-array 0)))]
        (is (= (class (byte-array 0))
               (class (encode message))))))
    (testing "it encodes a message"
      (let [message (-> (make-versioned-message "v1.0" {})
                        (set-data (byte-array 0)))]
      (is (= [1,                    ; PCP version
              1, 0 0 0 2, 123 125,  ; envelope chunk: chunk type, content size, content
              2, 0 0 0 0]           ; data chunk: chunk type, content size
             (vec (encode message))))))
    (testing "it adds debug type as an optional final chunk"
      (let [message (-> (make-versioned-message "v1.0" {})
                        (set-data (byte-array 0))
                        (set-debug (string->bytes "some")))]
        (is (= [1,                            ; PCP version
                1, 0 0 0 2, 123 125,          ; envelope chunk
                2, 0 0 0 0,                   ; data chunk
                3, 0 0 0 4, 115 111 109 101]  ; debug chunk: chunk type, content size, content
               (vec (encode message))))))
    (testing "it encodes the data chunk"
      (let [message (-> (make-versioned-message "v1.0" {})
                        (set-data (string->bytes "haha")))]
        (is (= [1,
                1, 0 0 0 2, 123 125,
                2, 0 0 0 4, 104 97 104 97]
               (vec (encode message))))))))

(deftest decode-test
  (testing "it only handles version 1 messages"
    (is (thrown+? [:type :puppetlabs.pcp.message/message-malformed]
                  (decode (byte-array [2])))))
  (testing "it insists on envelope chunk first"
    (is (thrown+? [:type :puppetlabs.pcp.message/message-invalid]
                  (decode (byte-array [1,
                                       2, 0 0 0 2, 123 125])))))
  (testing "it insists on a well-formed envelope"
    (is (thrown+? [:type :puppetlabs.pcp.message/envelope-malformed]
                  (decode (byte-array [1,
                                       1, 0 0 0 1, 123])))))
  (testing "it insists on a complete envelope"
    (is (thrown+? [:type :puppetlabs.pcp.message/envelope-invalid]
                  (decode (byte-array [1,
                                       1, 0 0 0 2, 123 125])))))
  ;; disable schema validations (both signature validations and explicit
  ;; calls to `schema.core/validate`) for the following tests as the byte
  ;; arrays used in them don't match the expected schemas for the sake
  ;; of brevity
  (s/without-fn-validation
    (with-redefs [schema.core/validate (fn [_ v] v)]
      (testing "it decodes the null message"
        (let [message (-> (make-versioned-message "v1.0" {})
                          (set-data (byte-array 0)))]
          (is (= (-> (message->envelope message)
                     (dissoc :id))
                 (-> (decode (byte-array [1, 1, 0 0 0 2, 123 125]))
                     message->envelope
                     (dissoc :id))))))
      (testing "data is accessible"
        (let [message (decode (byte-array [1,
                                           1, 0 0 0 2, 123 125,
                                           2, 0 0 0 3, 108 111 108]))]
          (is (= "lol" (String. (get-data message))))))
      (testing "debug is accessible"
        (let [message (decode (byte-array [1,
                                           1, 0 0 0 2, 123 125,
                                           2, 0 0 0 0,
                                           3, 0 0 0 3, 108 111 108]))]
          (is (= "lol" (String. (get-debug message)))))))))

(deftest encoder-roundtrip-test
  (testing "it can roundtrip data"
    (testing "from v1 to v1"
      (let [data (byte-array (map byte "hola"))
            encoded-msg (-> (make-versioned-message "v1.0" {:sender "pcp://client01.example.com/test"})
                            (set-data data)
                            encode)
            decoded (decode encoded-msg)]
        (is (= (vec (get-data decoded))
               (vec data)))))))
