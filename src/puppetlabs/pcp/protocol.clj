(ns puppetlabs.pcp.protocol
  (:require [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]))

(def ISO8601
  "Schema validates if string conforms to ISO8601"
  (s/pred ks/datetime? 'datetime?))

(def Uri
  "Schema for PCP node Uri"
  (s/pred (partial re-matches #"^pcp://[^/]*/[^/]+$") 'uri?))

(def ExplodedUri
  "Schema for PCP node Exploded Uri - an Uri split into the client and type components"
  (s/pair s/Str "client" s/Str "type"))

(def InventoryChange
  "Schema for a single change in inventory record"
  {:client Uri :change (s/enum -1 1)})

(defn uuid?
  [uuid]
  (re-matches #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" uuid))

(def MessageId
  "A message identifier"
  (s/pred uuid?))

(def v2-Envelope
  "Defines the envelope format of a v2 message"
  {:id           MessageId
   :message_type s/Str
   (s/optional-key :target) Uri
   (s/optional-key :sender) Uri
   (s/optional-key :in_reply_to) MessageId
   (s/optional-key :data) s/Any})

(def v1-Envelope
  "Defines the envelope format of a v1 message"
  {:id           MessageId
   (s/optional-key :in-reply-to) MessageId
   :sender       Uri
   :targets      [Uri]
   :message_type s/Str
   :expires      ISO8601
   (s/optional-key :destination_report) s/Bool})

(def AssociateResponse
  "Schema for http://puppetlabs.com/associate_response"
  {:id MessageId
   :success s/Bool
   (s/optional-key :reason) s/Str})

(def InventoryRequest
  "Data schema for http://puppetlabs.com/inventory_request"
  {:query [Uri]
   (s/optional-key :subscribe) s/Bool})

(def InventoryResponse
  "Data schema for http://puppetlabs.com/inventory_response"
  {:uris [Uri]})

(def InventoryUpdate
  "Data schema for http://puppetlabs.com/inventory_update"
  {:changes [InventoryChange]})

(def DestinationReport
  "Defines the data field for a destination report body"
  {:id MessageId
   :targets [Uri]})

(def ErrorMessage
  "Data schema for http://puppetlabs.com/error_message"
  {(s/optional-key :id) MessageId
   :description s/Str})

(def TTLExpiredMessage
  "Data schema for http://puppetlabs.com/ttl_expired"
  {:id MessageId})

(def VersionErrorMessage
  "Data schema for http://puppetlabs.com/version_error"
  {:id MessageId
   :target s/Str
   :reason s/Str})

(def DebugChunk
  "Data schema for a debug chunk"
  {:hops [{(s/required-key :server) Uri
           (s/optional-key :stage) s/Str
           (s/required-key :time) ISO8601}]})

(s/defn explode-uri :- ExplodedUri
  "Parse an Uri string into its component parts.  Raises if incomplete"
  [uri :- Uri]
  (str/split (subs uri 6) #"/"))

(s/defn uri-wildcard? :- (s/maybe ExplodedUri)
  [uri :- Uri]
  (let [chunks (explode-uri uri)]
    (if (some (partial = "*") chunks)
      chunks)))