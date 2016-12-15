(ns puppetlabs.pcp.message-v2
  (:require [cheshire.core :as cheshire]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pcp.protocol :refer [v2-Envelope ISO8601]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.i18n.core :as i18n]))

;; schemas for message validation
(def Message
  "Defines the message objects we're using"
  v2-Envelope)

(s/defn message->envelope :- v2-Envelope
  "Returns the map without any of the known 'private' keys.  Should
  map to an envelope schema."
  [message :- Message]
  message)

(s/defn get-data :- s/Any
  "Returns the data"
  [message :- Message]
  (get message :data))

(s/defn set-data :- Message
  "Sets the data"
  [message :- Message data :- s/Any]
  (assoc message :data data))

(s/defn make-message :- Message
  "Returns a new empty message structure"
  ([] (make-message {}))
  ([k v & kvs]
   (make-message (apply hash-map k v kvs)))
  ([opts]
   (into {:id (ks/uuid)
          :message_type ""}
         opts)))

(s/defn ^:always-validate encode :- String
  "Returns a text representation of a message for transmission.
  Always validates input and output."
  [message :- Message]
  (cheshire/generate-string (message->envelope message)))

(s/defn ^:always-validate decode :- Message
  "Returns a message object from a text format used for transmission.
  Always validates input and output."
  [text :- String]
  (cheshire/parse-string text true))
