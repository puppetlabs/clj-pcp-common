(ns puppetlabs.pcp.message
  (:require [org.clojars.smee.binary.core :as b]
            [cheshire.core :as cheshire]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.set :refer [rename-keys]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pcp.protocol :refer [v1-Envelope Envelope ISO8601]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.i18n.core :as i18n])
  (:import [java.nio.charset Charset]))

;; schemas for message validation
(def Message
  "Defines the message objects we're using"
  ;; NOTE(richardc) the overriding of :sender here is a bit janky, we
  ;; accept that we can have anything in memory, but we'll check the
  ;; Envelope schema when interacting with the network
  (merge Envelope
         {:sender s/Str}))

(def v1-Message
  "Defines the message objects we're using"
  ;; NOTE(richardc) the overriding of :sender here is a bit janky, we
  ;; accept that we can have anything in memory, but we'll check the
  ;; Envelope schema when interacting with the network
  (merge v1-Envelope
         {:sender s/Str
          :_chunks {s/Keyword s/Any}}))

(def ByteArray
  "Schema for a byte-array"
  bytes)

(def FlagSet
  "Schema for the message flags"
  #{s/Keyword})

;; string <-> byte-array utilities

(def ^Charset conversion-charset
  "Charset used for the string <-> byte-array conversions"
  (Charset/forName "UTF-8"))

(defn ^bytes string->bytes
  "Returns an array of bytes from a string"
  [^String s]
  (.getBytes s conversion-charset))

(defn ^String bytes->string
  "Returns a string given a byte-array"
  [^bytes bytes]
  (String. bytes conversion-charset))

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

(s/defn get-data :- ByteArray
  "Returns the data from the data frame"
  [message :- Message]
  (get-in message [:_chunks :data :data] (byte-array 0)))

(s/defn get-debug :- ByteArray
  "Returns the data from the debug frame"
  [message :- Message]
  (get-in message [:_chunks :debug :data] (byte-array 0)))

(s/defn set-data :- Message
  "Sets the data for the data frame"
  ([message :- Message data :- ByteArray] (set-data message data #{}))
  ([message :- Message data :- ByteArray flags :- FlagSet]
   (assoc-in message [:_chunks :data] {:descriptor {:type 2
                                                    :flags flags}
                                       :data data})))

(s/defn set-debug :- Message
  "Sets the data for the debug frame"
  ([message :- Message data :- ByteArray] (set-debug message data #{}))
  ([message :- Message data :- ByteArray flags :- FlagSet]
   (assoc-in message [:_chunks :debug] {:descriptor {:type 3
                                                     :flags flags}
                                        :data data})))

(s/defn get-json-data :- s/Any
  "Returns the data from the data frame decoded from json"
  ([message] (get-json-data message "v2.0"))
  ([message :- Message
    version]
   (case version
     "v1.0" (-> (get-data message)
                bytes->string
                (cheshire/parse-string true))
     (:data message))))

(s/defn get-json-debug :- s/Any
  "Returns the data from the debug frame decoded from json"
  [message :- Message]
  (-> (get-debug message)
      bytes->string
      (cheshire/parse-string true)))

(s/defn set-json-data :- Message
  "Sets the data to be the json byte-array version of data"
  [message :- Message data :- s/Any]
  (set-data message (string->bytes (cheshire/generate-string data))))

(s/defn set-json-debug :- Message
  "Sets the debug data to be the json byte-array version of data"
  [message :- Message data :- s/Any]
  (set-debug message (string->bytes (cheshire/generate-string data))))

(s/defn make-message :- Message
  "Returns a new empty message structure"
  [& args]
  (into {:id (ks/uuid)
         :target []
         :message_type ""
         :sender ""
         :_chunks {}}
        (apply hash-map args)))

(defn make-versioned-message
  [version {:keys [message_type target sender data in_reply_to]}]
  (let [target-kwd (if (= "v2.0" version)
                     :target
                     :targets)
        add-data-fn (if (= "v2.0" version)
                      (fn [message]
                        (cond-> message
                          data (assoc :data data)
                          true (dissoc :_chunks)))
                      (fn [message]
                        (cond-> message
                          data (set-json-data data))))]
    (cond-> (make-message)
      true (rename-keys {:target target-kwd})
      target (assoc target-kwd target)
      message_type (assoc :message_type message_type)
      sender (assoc :sender sender)
      in_reply_to (assoc :in_reply_to in_reply_to)
      true add-data-fn)))

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
  [message]
  (s/validate (s/either Message v1-Message) message)
  (let [stream (java.io.ByteArrayOutputStream.)
        envelope (string->bytes (cheshire/generate-string (message->envelope message)))
        chunks (into []
                     (remove nil? [{:descriptor {:type 1}
                                    :data envelope}
                                   (get-in message [:_chunks :data])
                                   (get-in message [:_chunks :debug])]))]
    (b/encode message-codec stream {:chunks chunks})
    (.toByteArray stream)))

;; TODO change the approach with envelope-schema

(s/defn decode :- Message
  "Returns a message object from a network format message"
  [bytes]
  (let [stream (java.io.ByteArrayInputStream. bytes)
        decoded (try+
                  (b/decode message-codec stream)
                  (catch Throwable _
                    (throw+ {:type ::message-malformed
                             :message (:message &throw-context)})))]
    (if (not (= 1 (get-in (first (:chunks decoded)) [:descriptor :type])))
      (throw+ {:type ::message-invalid
               :message (i18n/trs "first chunk should be type 1")}))
    (let [envelope-json (bytes->string (:data (first (:chunks decoded))))
          envelope (try+
                     (cheshire/decode envelope-json true)
                     (catch Exception _
                       (throw+ {:type ::envelope-malformed
                                :message (:message &throw-context)})))
          data-chunk (second (:chunks decoded))
          data-frame (or (:data data-chunk) (byte-array 0))
          data-flags (or (get-in data-chunk [:descriptor :flags]) #{})]
      (try+ (s/validate (s/either v1-Envelope Envelope) envelope)
            (catch Object _
              (throw+ {:type ::envelope-invalid
                       :message (:message &throw-context)})))
      ;; terrible hack to accomodate null message decoding
      (if (and (= 1 (count (:chunks decoded))) (not= {} envelope))
        envelope
        (let [message (set-data (merge (make-versioned-message
                                         "v1.0"
                                         {:target [] :message_type ""
                                          :sender ""})
                                       envelope)
                                data-frame data-flags)]
          (if-let [debug-chunk (get (:chunks decoded) 2)]
            (let [debug-frame (or (:data debug-chunk) (byte-array 0))
                  debug-flags (or (get-in debug-chunk [:descriptor :flags]) #{})]
              (set-debug message debug-frame debug-flags))
            message))))))
