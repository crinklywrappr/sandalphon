(ns crinklywrappr.sandalphon.protocols
  "Core protocols for Sandalphon Vulkan wrapper.")

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol IVulkanHandle
  "Protocol for Vulkan objects that wrap native handles in metadata."
  (handle [this] "Returns the native Vulkan handle stored in metadata."))

(defprotocol PropertyQueryable
  "Protocol for Vulkan objects that can return their properties."
  (properties [this] "Returns the properties of a Vulkan object."))

(defprotocol Indexable
  "Protocol for Vulkan objects that have an index in an enumerated collection."
  (index [this] "Returns the index of the object in the enumerated collection from which it was derived."))
