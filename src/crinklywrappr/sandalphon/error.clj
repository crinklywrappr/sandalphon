(ns crinklywrappr.sandalphon.error
  "Vulkan error handling utilities."
  (:import [org.lwjgl.vulkan VK10]))

(def ^:private vk-error-messages
  "Maps Vulkan error codes to human-readable messages."
  {VK10/VK_ERROR_OUT_OF_HOST_MEMORY "Out of host memory"
   VK10/VK_ERROR_OUT_OF_DEVICE_MEMORY "Out of device memory"
   VK10/VK_ERROR_INITIALIZATION_FAILED "Initialization failed"
   VK10/VK_ERROR_DEVICE_LOST "Device lost"
   VK10/VK_ERROR_MEMORY_MAP_FAILED "Memory map failed"
   VK10/VK_ERROR_LAYER_NOT_PRESENT "Layer not present"
   VK10/VK_ERROR_EXTENSION_NOT_PRESENT "Extension not present"
   VK10/VK_ERROR_FEATURE_NOT_PRESENT "Feature not present"
   VK10/VK_ERROR_INCOMPATIBLE_DRIVER "Cannot find a compatible Vulkan installable client driver (ICD)"
   VK10/VK_ERROR_TOO_MANY_OBJECTS "Too many objects"
   VK10/VK_ERROR_FORMAT_NOT_SUPPORTED "Format not supported"
   VK10/VK_ERROR_FRAGMENTED_POOL "Fragmented pool"})

(defn error-message
  "Returns a human-readable error message for a Vulkan result code."
  [result]
  (get vk-error-messages result (str "Unknown Vulkan error (code: " result ")")))

(defn check-result
  "Checks a Vulkan result code and throws on error.
  Returns the result if successful (VK_SUCCESS)."
  [result]
  (when-not (= result VK10/VK_SUCCESS)
    (throw (ex-info (error-message result) {:result result})))
  result)
