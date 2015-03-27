# clj-cthun-message

CThun message codec

https://github.com/puppetlabs/cthun-specifications


# Installation

The jar is distributed via the internal nexus server, to use it add
the following to your project.clj

    :dependencies [[puppetlabs/clj-cthun-message "0.0.1"]]

    :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                   ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

# Usage

``` clojure
(ns example
   (:require [puppetlabs.cthun.message :as message]))

(def message (message/make-message))
```

## make-message

``` clojure
(make-message)
```

Returns a message matching the puppetlabs.cthun.message/Message
schema.

## add-hop

``` clojure
(add-hop message stage)
(add-hop message stage timestamp)
```

Returns a message with a hop added.

## set-expiry

``` clojure
(set-expiry message timestamp)
(set-expiry message count unit)
```

Returns a message with the expiry set.

## get-data

``` clojure
(get-data message)
```

Returns the data chunk of a message

## set-data

``` clojure
(set-data message (byte-array 0))
```

Set the data chunk of a message


## get-json-data

``` clojure
(get-json-data message)
```

Returns the data chunk of a message decoded from json


## get-json-data

``` clojure
(set-json-data message {})
```

Set the data chunk of a message to the the json encoding of third argument.

## encode

``` clojure
(encode message)
```

Returns the network representation of the message

## decode

``` clojure
(decode (byte-array 0))
```

Returns a message decoded from its network representation
