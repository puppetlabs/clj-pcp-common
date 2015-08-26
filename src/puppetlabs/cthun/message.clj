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
          :_hops [MessageHop]
          :_data_frame ByteArray
          :_data_flags FlagSet
          :_target s/Str}))

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

(s/defn ^:always-validate make-message :- Message
  "Returns a new empty message structure"
  []
  {:id (ks/uuid)
   :targets []
   :message_type ""
   :sender ""
   :expires "1970-01-01T00:00:00.000Z"
   :_hops []
   :_data_frame (byte-array 0)
   :_data_flags #{}
   :_target ""})

(defn filter-private
  "Returns the map without any of the known 'private' keys.  Should
  map to an envelope schema."
  [message]
  (-> message
      (dissoc :_target)
      (dissoc :_data_frame)
      (dissoc :_data_flags)
      (dissoc :_hops)))

(s/defn ^:always-validate add-hop :- Message
  "Returns the message with a hop for the specified 'stage' added."
  ([message :- Message stage :- s/Str] (add-hop message stage (ks/timestamp)))
  ([message :- Message stage :- s/Str timestamp :- ISO8601]
   ;; TODO(richardc) this server field should come from the cert of this instance
     (let [hop {:server "cth://fake/server"
                :time   timestamp
                :stage  stage}
           hops (vec (:_hops message))
           new-hops (conj hops hop)]
       (assoc message :_hops new-hops))))

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
  (:_data_frame message))

(s/defn ^:always-validate set-data :- Message
  "Sets the data for the data frame"
  ([message :- Message data :- ByteArray ] (set-data message data #{}))
  ([message :- Message data :- ByteArray flags :- FlagSet ]
   (-> message
       (assoc :_data_frame data)
       (assoc :_data_flags flags))))

(s/defn ^:always-validate get-json-data :- s/Any
  "Returns the data from the data frame decoded from json"
  [message :- Message]
  (let [data (get-data message)
        decoded (cheshire/parse-string (bytes->string data) true)]
    decoded))

(s/defn ^:always-validate set-json-data :- Message
  "Sets the data to be the json byte-array version of data"
  [message :- Message data :- s/Any]
  (set-data message (string->bytes (cheshire/generate-string data))))

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
  "Returns a byte-array containing the message in network"
  [message :- Message]
  (let [stream (java.io.ByteArrayOutputStream.)
        hops (:_hops message)
        envelope (string->bytes (cheshire/generate-string (filter-private message)))
        debug-data (string->bytes (cheshire/generate-string {:hops hops}))
        data-frame (or (:_data_frame message) (byte-array 0))
        data-flags (or (:_data_flags message) #{})]
    (b/encode message-codec stream
              {:chunks (remove nil? [{:descriptor {:type 1}
                                      :data envelope}
                                     {:descriptor {:type 2
                                                   :flags data-flags}
                                      :data data-frame}
                                     (if hops
                                       {:descriptor {:type 3}
                                        :data debug-data})])})
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
      (set-data (merge (make-message) envelope) data-frame data-flags))))
