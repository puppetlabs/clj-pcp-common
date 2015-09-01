# 0.4.0

* Renamed from former codenames to new component names.

# 0.2.0

* Removed server state from Message (CTH-328)
* Removed add-hops function
* Added add-debug add-json-debug functions
* Now verify that MessageId looks like a uuid

# 0.1.0

* Added prismatic schema schema.core/defn decorations to the rest of
  the public api functions (CTH-206)
* Renamed Endpoint -> Uri, and reworked envelope schema for changes to
  cthun specifications (CTH-210)

# 0.0.1

* Initial internal release, extracted from cthun server (CTH-185)
* Added set-expiry
* Added Message schema and made make-message constrain to it.
