(ns puppetlabs.cthun.message
  (:require [org.clojars.smee.binary.core :as b]
            [cheshire.core :as cheshire]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

;; schemas for envelope validation

(def ISO8601
  "Schema validates if string conforms to ISO8601"
  (s/pred ks/datetime? 'datetime?))

(def Uri
  "Schema for Cthun node Uri"
  (s/pred (partial re-matches #"^cth://[^/]*/[^/]+$") 'uri?))

(def MessageHop
  "Map that describes a step in message delivery"
  {(s/required-key :server) Uri
   (s/optional-key :stage) s/Str
   (s/required-key :time) ISO8601})

(defn uuid?
  [uuid]
  (re-matches #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" uuid))

(def MessageId
  "A message identfier"
  (s/pred uuid?))

(def Envelope
  "Defines the envelope format of a v2 message"
  {:id           MessageId
   :sender       Uri
   :targets      [Uri]
   :message_type s/Str
   :expires      ISO8601
   (s/optional-key :destination_report) s/Bool})

(def ByteArray
  "Schema for a byte-array"
  (class (byte-array 0)))

(def FlagSet
  "Schema for the message flags"
   #{s/Keyword})

(def Message
  "Defines the message objects we're using"
  ;; NOTE(richardc) the overriding of :sender here is a bit janky, we
  ;; accept that we can have anything in memory, but we'll check the
  ;; Envelope schema when interacting with the network
  (merge Envelope
         {:sender s/Str
          :_chunks {s/Keyword s/Any}}))

;; string<->byte-array utilities

(defn string->bytes
  "Returns an array of bytes from a string"
  [s]
  (byte-array (map byte s)))

(defn bytes->string
  "Returns a string given a byte-array"
  [bytes]
  (String. bytes))

;; abstract message manipulation
(s/defn message->envelope :- Envelope
  "Returns the map without any of the known 'private' keys.  Should
  map to an envelope schema."
  [message :- Message]
  (dissoc message :_chunks))

(defn filter-private
  "Deprecated, use message->envelope if you need"
  {:deprecated "0.2.0"}
  [message] (message->envelope message))

(s/defn ^:always-validate set-expiry :- Message
  "Returns a message with new expiry"
  ([message :- Message number :- s/Int unit :- s/Keyword]
   (let [expiry (condp = unit
                  :seconds (t/from-now (t/seconds number))
                  :hours   (t/from-now (t/hours number))
                  :days    (t/from-now (t/days number)))
         expires (tf/unparse (tf/formatters :date-time) expiry)]
         (set-expiry message expires)))
  ([message :- Message timestamp :- ISO8601]
   (assoc message :expires timestamp)))

(s/defn ^:always-validate get-data :- ByteArray
  "Returns the data from the data frame"
  [message :- Message]
  (get-in message [:_chunks :data :data] (byte-array 0)))

(s/defn ^:always-validate get-debug :- ByteArray
  "Returns the data from the debug frame"
  [message :- Message]
  (get-in message [:_chunks :debug :data] (byte-array 0)))

(s/defn ^:always-validate set-data :- Message
  "Sets the data for the data frame"
  ([message :- Message data :- ByteArray] (set-data message data #{}))
  ([message :- Message data :- ByteArray flags :- FlagSet]
   (assoc-in message [:_chunks :data] {:descriptor {:type 2
                                                    :flags flags}
                                       :data data})))

(s/defn ^:always-validate set-debug :- Message
  "Sets the data for the debug frame"
  ([message :- Message data :- ByteArray] (set-debug message data #{}))
  ([message :- Message data :- ByteArray flags :- FlagSet]
   (assoc-in message [:_chunks :debug] {:descriptor {:type 3
                                                     :flags flags}
                                        :data data})))

(s/defn ^:always-validate get-json-data :- s/Any
  "Returns the data from the data frame decoded from json"
  [message :- Message]
  (let [data (get-data message)
        decoded (cheshire/parse-string (bytes->string data) true)]
    decoded))

(s/defn ^:always-validate get-json-debug :- s/Any
  "Returns the data from the debug frame decoded from json"
  [message :- Message]
  (let [data (get-debug message)
        decoded (cheshire/parse-string (bytes->string data) true)]
    decoded))

(s/defn ^:always-validate set-json-data :- Message
  "Sets the data to be the json byte-array version of data"
  [message :- Message data :- s/Any]
  (set-data message (string->bytes (cheshire/generate-string data))))

(s/defn ^:always-validate set-json-debug :- Message
  "Sets the debug data to be the json byte-array version of data"
  [message :- Message data :- s/Any]
  (set-debug message (string->bytes (cheshire/generate-string data))))

(s/defn ^:always-validate make-message :- Message
  "Returns a new empty message structure"
  []
  (let [message {:id (ks/uuid)
                 :targets []
                 :message_type ""
                 :sender ""
                 :expires "1970-01-01T00:00:00.000Z"
                 :_chunks {}}]
    (set-data message (byte-array 0))))

;; message encoding/codecs

(def flag-bits
  {2r1000 :unused1
   2r0100 :unused2
   2r0010 :unused3
   2r0001 :unused4})

(defn encode-descriptor
  "Returns a binary representation of a chunk descriptor"
  [type]
  (let [type-bits (:type type)
        flag-set  (:flags type)
        flags (apply bit-or 0 0 (remove nil? (map (fn [[mask name]] (if (contains? flag-set name) mask)) flag-bits)))
        byte (bit-or type-bits (bit-shift-left flags 4))]
    byte))

(defn decode-descriptor
  "Returns the clojure object for a chunk descriptor from a byte"
  [byte]
  (let [type (bit-and 0x0F byte)
        flags (bit-shift-right (bit-and 0xF0 byte) 4)
        flag-set (into #{} (remove nil? (map (fn [[mask name]] (if (= mask (bit-and mask flags)) name)) flag-bits)))]
    {:flags flag-set
     :type type}))

(def descriptor-codec
  (b/compile-codec :byte encode-descriptor decode-descriptor))

(def chunk-codec
  (b/ordered-map
   :descriptor descriptor-codec
   :data (b/blob :prefix :int-be)))

(def message-codec
  (b/ordered-map
   :version (b/constant :byte 1)
   :chunks (b/repeated chunk-codec)))

(s/defn encode :- ByteArray
  [message :- Message]
  (let [stream (java.io.ByteArrayOutputStream.)
        envelope (string->bytes (cheshire/generate-string (message->envelope message)))
        chunks (into []
                     (remove nil? [{:descriptor {:type 1}
                                    :data envelope}
                                   (get-in message [:_chunks :data])
                                   (get-in message [:_chunks :debug])]))]
    (b/encode message-codec stream {:chunks chunks})
    (.toByteArray stream)))

(s/defn ^:always-validate decode :- Message
  "Returns a message object from a network format message"
  [bytes :- ByteArray]
  (let [stream (java.io.ByteArrayInputStream. bytes)
        decoded (try+
                 (b/decode message-codec stream)
                 (catch Throwable _
                   (throw+ {:type ::message-malformed
                            :message (:message &throw-context)})))]
    (if (not (= 1 (get-in (first (:chunks decoded)) [:descriptor :type])))
      (throw+ {:type ::message-invalid
               :message "first chunk should be type 1"}))
    (let [envelope-json (bytes->string (:data (first (:chunks decoded))))
          envelope (try+
                    (cheshire/decode envelope-json true)
                    (catch Exception _
                      (throw+ {:type ::envelope-malformed
                               :message (:message &throw-context)})))
          data-chunk (second (:chunks decoded))
          data-frame (or (:data data-chunk) (byte-array 0))
          data-flags (or (get-in data-chunk [:descriptor :flags]) #{})]
      (try+ (s/validate Envelope envelope)
            (catch Throwable _
              (throw+ {:type ::envelope-invalid
                       :message (:message &throw-context)})))
      (let [message (set-data (merge (make-message) envelope) data-frame data-flags)]
        (if-let [debug-chunk (get (:chunks decoded) 2)]
          (let [debug-frame (or (:data debug-chunk) (byte-array 0))
                debug-flags (or (get-in debug-chunk [:descriptor :flags]) #{})]
            (set-debug message debug-frame debug-flags))
          message)))))
