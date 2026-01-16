(ns crinklywrappr.sandalphon.core
  "Low-level Vulkan bindings for Clojure, following Vulkano's design philosophy."
  (:require [clojure.set :as st]
            [clojure.string :as sg]
            [clojure.reflect :as reflect]
            [camel-snake-kebab.core :as csk])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan VK VkApplicationInfo VkInstanceCreateInfo VkInstance
            VkPhysicalDevice VkPhysicalDeviceProperties VkPhysicalDeviceFeatures
            VkLayerProperties VkExtensionProperties VkPhysicalDeviceGroupProperties
            VkQueueFamilyProperties VkDebugUtilsMessengerCallbackEXT
            VkDebugUtilsMessengerCallbackDataEXT VkDebugUtilsMessengerCreateInfoEXT
            VkDeviceCreateInfo VkDeviceQueueCreateInfo VkDevice VkDeviceGroupDeviceCreateInfo
            VkQueue VkBufferCreateInfo
            EXTDebugUtils VK10 VK11 KHRVideoDecodeQueue KHRVideoEncodeQueue NVOpticalFlow]
           [org.lwjgl.util.vma Vma VmaAllocatorCreateInfo VmaAllocationCreateInfo VmaVulkanFunctions]))

;; ============================================================================
;; Error Handling
;; ============================================================================

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

(defn- error-message
  "Returns a human-readable error message for a Vulkan result code."
  [result]
  (get vk-error-messages result (str "Unknown Vulkan error (code: " result ")")))

(defn- check-result
  "Checks a Vulkan result code and throws on error."
  [result]
  (when-not (= result VK10/VK_SUCCESS)
    (throw (ex-info (error-message result) {:result result})))
  result)

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol IVulkanHandle
  "Protocol for Vulkan objects that wrap native handles in metadata."
  (handle [this] "Returns the native Vulkan handle stored in metadata."))

(defprotocol PropertyQueryable
  (properties [this] "returns the properties of a vulkan object"))

(defprotocol Indexable
  (index [this] "returns the index of the object in the enumerated collection from which it was derived"))

;; ============================================================================
;; Version Information - Step 1
;; ============================================================================

(defprotocol IVulkanVersion
  "Protocol to provide Vulkan Version numbers in a packed format"
  (pack [this] "Returns an integer representing a packed Vulkan version number"))

(defrecord VulkanVersion [major minor patch]
  Object
  (toString [_this] (str major "." minor "." patch))

  IVulkanVersion
  (pack [_this]
    (bit-or (bit-shift-left major 22)
            (bit-shift-left minor 12)
            patch)))

(extend-protocol IVulkanVersion
  Long
  (pack [this] (int this))
  Integer
  (pack [this] this))

(defn create-vulkan-version
  "Creates a VulkanVersion from a packed Vulkan version integer.

  If version is nil, returns VulkanVersion for 1.0.0 (handles cases where
  Vulkan 1.0 drivers don't report version info).

  Example:
    (create-vulkan-version 4211009)
    ;; => #crinklywrappr.sandalphon.core.VulkanVersion{:major 1, :minor 4, :patch 321}

    (str (create-vulkan-version 4211009))
    ;; => \"1.4.321\"

    (create-vulkan-version nil)
    ;; => #crinklywrappr.sandalphon.core.VulkanVersion{:major 1, :minor 0, :patch 0}"
  [version]
  (let [v (or version VK10/VK_API_VERSION_1_0)]
    (->VulkanVersion
     (bit-shift-right v 22)
     (bit-and (bit-shift-right v 12) 0x3FF)
     (bit-and v 0xFFF))))

(defn vulkan-instance-version
  "Returns the highest Vulkan instance version supported by this system.

  Returns a VulkanVersion record with :major, :minor, and :patch fields.

  Example:
    (vulkan-instance-version)
    ;; => #crinklywrappr.sandalphon.core.VulkanVersion{:major 1, :minor 4, :patch 321}

    (str (vulkan-instance-version))
    ;; => \"1.4.321\""
  []
  (create-vulkan-version (VK/getInstanceVersionSupported)))

;; ============================================================================
;; Layer Properties
;; ============================================================================

(defn- query-layer-properties
  "Queries all layer properties.

  Returns a vector of maps containing:
    :layer-name              - Layer name
    :spec-version            - Vulkan API version (VulkanVersion)
    :implementation-version  - Layer implementation version (raw integer, vendor-specific)
    :description             - Human-readable description"
  [^MemoryStack stack]
  (let [layer-count-ptr (.mallocInt stack 1)]

    ;; First call: get layer count
    (check-result (VK10/vkEnumerateInstanceLayerProperties layer-count-ptr nil))

    (let [layer-count (.get layer-count-ptr 0)]
      (when (pos? layer-count)
        ;; Second call: get layers
        (let [layer-props (VkLayerProperties/malloc layer-count stack)]
          (check-result (VK10/vkEnumerateInstanceLayerProperties layer-count-ptr layer-props))

          ;; Convert to maps
          (vec
           (for [i (range layer-count)]
             (let [layer (.get layer-props i)]
               {:layer-name (.layerNameString layer)
                :spec-version (create-vulkan-version (.specVersion layer))
                :implementation-version (.implementationVersion layer)
                :description (.descriptionString layer)}))))))))

(defrecord Layer [layer-name spec-version description]
  PropertyQueryable
  (properties [this]
    {:layer-name layer-name
     :spec-version spec-version
     :description description
     :implementation-version (-> this meta :implementation-version)}))

(defn layers
  "Enumerates all available Vulkan instance layers.

  Each Layer record contains:
    :layer-name    - Layer name (e.g., \"VK_LAYER_KHRONOS_validation\")
    :spec-version  - Vulkan API version this layer was written against
    :description   - Human-readable description

  Additional properties are available via (properties layer):
    :implementation-version  - Layer implementation version (raw integer, vendor-specific)

  The validation layer (VK_LAYER_KHRONOS_validation) is essential for development
  as it catches API misuse, memory errors, and threading issues.

  Returns a vector of Layer records.

  Example:
    (layers)
    ;; => [#crinklywrappr.sandalphon.core.Layer{:layer-name \"VK_LAYER_KHRONOS_validation\" ...}]"
  []
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [all-layer-props (query-layer-properties stack)]
      (vec
       (for [props all-layer-props]
         (with-meta (->Layer (:layer-name props)
                             (:spec-version props)
                             (:description props))
           {:implementation-version (:implementation-version props)}))))))

(defrecord Extension [extension-name spec-version])

(defn- query-extension-properties
  "Queries extension properties for a specific layer (or nil for core extensions).

  Parameters:
    layer-name - Layer name string (e.g., \"VK_LAYER_KHRONOS_validation\"), or nil for core extensions
    stack      - MemoryStack for allocations

  Returns a set of Extension records."
  [layer-name ^MemoryStack stack]
  (let [count-ptr (.mallocInt stack 1)
        layer-name-utf8 (when layer-name (.UTF8 stack layer-name))]

    ;; First call: get extension count
    (check-result (VK10/vkEnumerateInstanceExtensionProperties layer-name-utf8 count-ptr nil))

    (let [ext-count (.get count-ptr 0)]
      (when (pos? ext-count)
        ;; Second call: get extensions
        (let [ext-props (VkExtensionProperties/malloc ext-count stack)]
          (check-result (VK10/vkEnumerateInstanceExtensionProperties layer-name-utf8 count-ptr ext-props))

          (set
           (for [i (range ext-count)]
             (let [ext (.get ext-props i)]
               (->Extension (.extensionNameString ext)
                            (.specVersion ext))))))))))

(defn supported-extensions
  "Returns extensions supported by layers.

  Each Extension record contains:
    :extension-name - Extension name (e.g., \"VK_EXT_debug_utils\")
    :spec-version   - Extension specification version (raw integer)

  Arities:
    ()              - Returns core extensions only
    (layer)         - Returns extensions for a single layer (or core if nil)
    (layer & more)  - Returns union of extensions for all layers (excludes core unless nil passed)

  To union core extensions with layer extensions:
    (apply supported-extensions nil layers)

  Examples:
    (supported-extensions)                    ;; Core extensions only
    (supported-extensions validation-layer)   ;; Validation layer extensions only
    (supported-extensions layer1 layer2)      ;; Union of layer1 and layer2
    (apply supported-extensions nil [layer1]) ;; Core + layer1"
  ([]
   (with-open [^MemoryStack stack (MemoryStack/stackPush)]
     (query-extension-properties nil stack)))

  ([layer]
   (with-open [^MemoryStack stack (MemoryStack/stackPush)]
     (query-extension-properties (when layer (:layer-name layer)) stack)))

  ([layer & layers]
   (with-open [^MemoryStack stack (MemoryStack/stackPush)]
     (transduce
      (map (fn [l] (query-extension-properties (when l (:layer-name l)) stack)))
      st/union (cons layer layers)))))

;; ============================================================================
;; Random Name Generation
;; ============================================================================

(def ^:private adjectives
  ["admiring" "adoring" "affectionate" "agitated" "amazing" "angry" "awesome"
   "beautiful" "blissful" "bold" "boring" "brave" "busy" "charming" "clever"
   "cool" "compassionate" "competent" "condescending" "confident" "cranky"
   "crazy" "dazzling" "determined" "distracted" "dreamy" "eager" "ecstatic"
   "elastic" "elated" "elegant" "eloquent" "epic" "exciting" "fervent" "festive"
   "flamboyant" "focused" "friendly" "frosty" "funny" "gallant" "gifted" "goofy"
   "gracious" "great" "happy" "hardcore" "heuristic" "hopeful" "hungry" "infallible"
   "inspiring" "intelligent" "interesting" "jolly" "jovial" "keen" "kind" "laughing"
   "loving" "lucid" "magical" "mystifying" "modest" "musing" "naughty" "nervous"
   "nice" "nifty" "nostalgic" "objective" "optimistic" "peaceful" "pedantic"
   "pensive" "practical" "priceless" "quirky" "quizzical" "recursing" "relaxed"
   "reverent" "romantic" "sad" "serene" "sharp" "silly" "sleepy" "stoic" "strange"
   "stupefied" "suspicious" "sweet" "tender" "thirsty" "trusting" "unruffled"
   "upbeat" "vibrant" "vigilant" "vigorous" "wizardly" "wonderful" "xenodochial"
   "youthful" "zealous" "zen"])

(def ^:private scientists
  ["albattani" "allen" "almeida" "antonelli" "agnesi" "archimedes" "ardinghelli"
   "aryabhata" "austin" "babbage" "banach" "banzai" "bardeen" "bartik" "bassi"
   "beaver" "bell" "benz" "bhabha" "bhaskara" "black" "blackburn" "blackwell"
   "bohr" "booth" "borg" "bose" "bouman" "boyd" "brahmagupta" "brattain" "brown"
   "buck" "burnell" "cannon" "carson" "cartwright" "carver" "cerf" "chandrasekhar"
   "chaplygin" "chatelet" "chatterjee" "chebyshev" "cohen" "chaum" "clarke" "colden"
   "cori" "cray" "curran" "curie" "darwin" "davinci" "dewdney" "dhawan" "diffie"
   "dijkstra" "dirac" "driscoll" "dubinsky" "easley" "edison" "einstein" "elbakyan"
   "elgamal" "elion" "ellis" "engelbart" "euclid" "euler" "faraday" "feistel"
   "fermat" "fermi" "feynman" "franklin" "gagarin" "galileo" "gates" "gauss"
   "germain" "goldberg" "goldstine" "goldwasser" "golick" "goodall" "gould" "greider"
   "grothendieck" "haibt" "hamilton" "haslett" "hawking" "hellman" "heisenberg"
   "hermann" "herschel" "hertz" "heyrovsky" "hodgkin" "hofstadter" "hoover" "hopper"
   "hugle" "hypatia" "ishizaka" "jackson" "jang" "jemison" "jennings" "jepsen"
   "johnson" "joliot" "jones" "kalam" "kapitsa" "kare" "keldysh" "keller" "kepler"
   "khayyam" "khorana" "kilby" "kirch" "knuth" "kowalevski" "lalande" "lamarr"
   "lamport" "leakey" "leavitt" "lederberg" "lehmann" "lewin" "lichterman" "liskov"
   "lovelace" "lumiere" "mahavira" "margulis" "matsumoto" "maxwell" "mayer" "mccarthy"
   "mcclintock" "mclaren" "mclean" "mcnulty" "mendel" "mendeleev" "meitner" "meninsky"
   "merkle" "mestorf" "mirzakhani" "moore" "morse" "murdock" "moser" "napier" "nash"
   "neumann" "newton" "nightingale" "nobel" "noether" "northcutt" "noyce" "panini"
   "pare" "pascal" "pasteur" "payne" "perlman" "pike" "poincare" "poitras" "proskuriakova"
   "ptolemy" "raman" "ramanujan" "ride" "montalcini" "ritchie" "rhodes" "robinson"
   "roentgen" "rosalind" "rubin" "saha" "sammet" "sanderson" "satoshi" "shamir"
   "shannon" "shaw" "shirley" "shockley" "shtern" "sinoussi" "snyder" "solomon"
   "spence" "stonebraker" "sutherland" "swanson" "swartz" "swirles" "taussig"
   "tereshkova" "tesla" "tharp" "thompson" "torvalds" "tu" "turing" "varahamihira"
   "vaughan" "visvesvaraya" "volhard" "villani" "wescoff" "wilbur" "wiles" "williams"
   "williamson" "wilson" "wing" "wozniak" "wright" "wu" "yalow" "yonath" "zhukovsky"])

(defn random-name
  "Generates a random Docker-style adjective-scientist name."
  []
  (str (rand-nth adjectives) "-" (rand-nth scientists)))

;; ============================================================================
;; Instance - Step 2
;; ============================================================================

(defn- strings->pointer-buffer
  "Converts a seq of items to a PointerBuffer of UTF8 strings.

  Parameters:
    items   - Sequence of items to convert
    name-fn - Function to extract string name from each item
    stack   - MemoryStack for allocation

  Returns PointerBuffer or nil if items is empty."
  [items name-fn ^MemoryStack stack]
  (when (seq items)
    (let [buffer (.mallocPointer stack (count items))]
      (doseq [item items]
        (.put buffer (.UTF8 stack (name-fn item))))
      (.flip buffer))))

(defrecord Instance [config]
  java.io.Closeable
  (close [this]
    (when-let [^VkInstance vk-handle (handle this)]
      ;; Destroy all debug messengers first
      (when-let [messengers (-> this meta :messengers deref)]
        (doseq [{:keys [handle]} messengers]
          (EXTDebugUtils/vkDestroyDebugUtilsMessengerEXT vk-handle handle nil)))
      ;; Then destroy the instance
      (VK10/vkDestroyInstance vk-handle nil)))

  IVulkanHandle
  (handle [this]
    (-> this meta :handle)))

(defn instance!
  "Creates a Vulkan Instance.

  The instance must be destroyed when no longer needed. Use with-open for
  automatic cleanup:

    (with-open [inst (instance! :app-name \"My App\")]
      (do-work inst))

  Or manually call (.close instance).

  Options:
    :app-name        - Application name (string, defaults to random Docker-style name)
    :app-version     - Application version (integer, default 0)
    :engine-name     - Engine name (string, optional)
    :engine-version  - Engine version (integer, default 0)
    :api-version     - Vulkan API version (integer, defaults to max supported)
    :layers          - Vector of Layer records from layers function
    :extensions      - Vector of Extension records from supported-extensions function

  Returns an Instance record with the VkInstance handle stored in metadata.

  Throws:
    - ExceptionInfo if instance creation fails (contains :result error code)"
  [& {:keys [app-name app-version engine-name engine-version api-version layers extensions]
      :or {app-name (random-name)
           app-version 0
           engine-version 0
           layers []
           extensions []}}]
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [;; Create application info
          packed-api-version (pack (or api-version (vulkan-instance-version)))
          app-info (-> (VkApplicationInfo/malloc stack)
                       (.sType$Default)
                       (.pNext 0)
                       (.pApplicationName (when app-name (.UTF8 stack app-name)))
                       (.applicationVersion app-version)
                       (.pEngineName (when engine-name (.UTF8 stack engine-name)))
                       (.engineVersion engine-version)
                       (.apiVersion packed-api-version))

          ;; Convert layers and extensions to PointerBuffers
          layer-names (strings->pointer-buffer layers :layer-name stack)
          extension-names (strings->pointer-buffer extensions :extension-name stack)

          ;; Create instance info
          inst-info (-> (VkInstanceCreateInfo/malloc stack)
                        (.sType$Default)
                        (.pNext 0)
                        (.flags 0)
                        (.pApplicationInfo app-info)
                        (.ppEnabledLayerNames layer-names)
                        (.ppEnabledExtensionNames extension-names))

          ;; Create instance
          instance-ptr (.mallocPointer stack 1)
          result (VK10/vkCreateInstance inst-info nil instance-ptr)]

      (check-result result)

      (let [vk-handle (VkInstance. (.get instance-ptr 0) inst-info)
            config {:app-name app-name
                    :app-version app-version
                    :engine-name engine-name
                    :engine-version engine-version
                    :api-version (create-vulkan-version packed-api-version)
                    :layers (vec layers)
                    :extensions (vec extensions)}]
        (with-meta (->Instance config)
          {:handle vk-handle
           :messengers (atom [])})))))

;; ============================================================================
;; Debug Messenger
;; ============================================================================

(defn- severity-keywords->bits
  "Converts severity keywords to Vulkan debug message severity bits."
  [severities]
  (reduce bit-or 0
          (map (fn [sev]
                 (case sev
                   :verbose EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT
                   :info EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT
                   :warning EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                   :error EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                   0))
               severities)))

(defn- message-type-keywords->bits
  "Converts message type keywords to Vulkan debug message type bits."
  [types]
  (reduce bit-or 0
          (map (fn [typ]
                 (case typ
                   :general EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                   :validation EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                   :performance EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
                   0))
               types)))

(defn debug!
  "Creates a debug messenger callback for validation layer output.

  The messenger will be automatically destroyed when the instance is closed.

  Options:
    :severity      - Vector of severity keywords (default [:warning :error])
                     Valid: :verbose, :info, :warning, :error
    :message-type  - Vector of message type keywords (default [:general :validation :performance])
                     Valid: :general, :validation, :performance
    :callback      - Callback function (severity message) -> nil
                     severity is keyword (:verbose/:info/:warning/:error)
                     message is string

  The instance must have been created with VK_EXT_debug_utils extension enabled.

  Example:
    (debug! instance
      :severity [:warning :error]
      :callback (fn [severity msg] (println severity msg)))"
  [^Instance instance & {:keys [severity message-type callback]
                         :or {severity [:warning :error]
                              message-type [:general :validation :performance]
                              callback (fn [sev msg] (println sev msg))}}]
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [^VkInstance vk-handle (handle instance)

          ;; Create the callback wrapper
          vk-callback (VkDebugUtilsMessengerCallbackEXT/create
                       (fn [severity-bits type-bits callback-data-ptr user-data]
                         (let [data (VkDebugUtilsMessengerCallbackDataEXT/create callback-data-ptr)
                               message (.pMessageString data)

                               ;; Convert severity bit to keyword
                               severity-kw (condp bit-and severity-bits
                                             EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT :error
                                             EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT :warning
                                             EXTDebugUtils/VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT :info
                                             :verbose)]

                           ;; Invoke user callback
                           (callback severity-kw message))

                         VK10/VK_FALSE)) ;; Don't abort on validation errors

          ;; Create messenger info
          messenger-info (-> (VkDebugUtilsMessengerCreateInfoEXT/malloc stack)
                             (.sType$Default)
                             (.messageSeverity (severity-keywords->bits severity))
                             (.messageType (message-type-keywords->bits message-type))
                             (.pfnUserCallback vk-callback))

          ;; Create the messenger
          messenger-ptr (.mallocLong stack 1)]

      (check-result (EXTDebugUtils/vkCreateDebugUtilsMessengerEXT
                     vk-handle messenger-info nil messenger-ptr))

      (let [messenger-handle (.get messenger-ptr 0)]
        ;; Add to instance's messenger collection
        (swap! (-> instance meta :messengers)
               conj {:handle messenger-handle
                     :callback vk-callback}) ;; Keep callback alive (prevent GC)

        instance)))) ;; Return instance for threading

;; ============================================================================
;; PhysicalDevice - Step 4
;; ============================================================================

(defn- device-type->keyword
  "Converts a Vulkan device type integer to a keyword."
  [device-type]
  (condp = device-type
    VK10/VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU :integrated-gpu
    VK10/VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU :discrete-gpu
    VK10/VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU :virtual-gpu
    VK10/VK_PHYSICAL_DEVICE_TYPE_CPU :cpu
    :other))

(defn- bytes->hex
  "Converts a byte buffer to a hex string."
  [byte-buffer]
  (apply str
         (for [i (range (.remaining byte-buffer))]
           (format "%02x" (bit-and 0xFF (.get byte-buffer i))))))

;; TODO: limits and sparse-properties need to be improved
(defn- query-device-properties
  "Queries all properties of a physical device."
  [^VkPhysicalDevice device ^MemoryStack stack]
  (let [props (VkPhysicalDeviceProperties/malloc stack)]
    (VK10/vkGetPhysicalDeviceProperties device props)
    {:device-name (.deviceNameString props)
     :device-type (device-type->keyword (.deviceType props))
     :api-version (create-vulkan-version (.apiVersion props))
     :driver-version (.driverVersion props)
     :vendor-id (.vendorID props)
     :device-id (.deviceID props)
     :pipeline-cache-uuid (bytes->hex (.pipelineCacheUUID props))
     :limits (.limits props)
     :sparse-properties (.sparseProperties props)}))

(defn- query-device-extension-properties
  "Queries extension properties for a physical device.

  Parameters:
    device     - VkPhysicalDevice handle
    layer-name - Layer name string (or nil for device's own extensions)
    stack      - MemoryStack for allocations

  Returns a set of Extension records."
  [^VkPhysicalDevice device layer-name ^MemoryStack stack]
  (let [count-ptr (.mallocInt stack 1)
        ^java.nio.ByteBuffer layer-name-buffer (when layer-name (.UTF8 stack layer-name))]
    ;; First call: get extension count
    (check-result (VK10/vkEnumerateDeviceExtensionProperties device layer-name-buffer count-ptr nil))

    (let [ext-count (.get count-ptr 0)]
      (when (pos? ext-count)
        ;; Second call: get extensions
        (let [ext-props (VkExtensionProperties/malloc ext-count stack)]
          (check-result (VK10/vkEnumerateDeviceExtensionProperties device layer-name-buffer count-ptr ext-props))

          (set
           (for [i (range ext-count)]
             (let [ext (.get ext-props i)]
               (->Extension (.extensionNameString ext)
                            (.specVersion ext))))))))))

(defrecord PhysicalDevice [device-name device-type api-version]
  IVulkanHandle
  (handle [this]
    (-> this meta :handle))

  PropertyQueryable
  (properties [this]
    (with-open [^MemoryStack stack (MemoryStack/stackPush)]
      (query-device-properties (handle this) stack))))

(defn physical-devices
  "Enumerates all physical devices available on the given Instance.

  Each PhysicalDevice contains:
    :device-name     - Human-readable device name (e.g., \"AMD Radeon Graphics\")
    :device-type     - One of :integrated-gpu, :discrete-gpu, :virtual-gpu, :cpu, :other
    :api-version     - Supported Vulkan API version (integer)

  The native VkPhysicalDevice handle is stored in metadata.

  Returns a vector of PhysicalDevice records."
  [^Instance instance]
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [device-count-ptr (.mallocInt stack 1)
          ^VkInstance instance-handle (handle instance)]

      ;; First call: get device count
      (check-result (VK10/vkEnumeratePhysicalDevices instance-handle device-count-ptr nil))

      (let [device-count (.get device-count-ptr 0)]
        (when (pos? device-count)
          ;; Second call: get devices
          (let [device-pointers (.mallocPointer stack device-count)]
            (check-result (VK10/vkEnumeratePhysicalDevices instance-handle device-count-ptr device-pointers))

            ;; Convert to PhysicalDevice records
            (vec
             (for [i (range device-count)]
               (let [vk-device-handle (VkPhysicalDevice. (.get device-pointers i) instance-handle)
                     {:keys [device-name device-type api-version]} (query-device-properties vk-device-handle stack)]
                 (with-meta (->PhysicalDevice device-name device-type api-version)
                   {:handle vk-device-handle
                    :instance instance}))))))))))

(defrecord PhysicalDeviceGroup [devices subset-allocation]
  PropertyQueryable
  (properties [_this]
    {:devices (map properties devices)
     :subset-allocation subset-allocation}))

(defn physical-device-groups
  "Enumerates physical device groups (Vulkan 1.1 feature).

  Device groups represent sets of physical devices that can work together,
  typically for multi-GPU configurations. Each group contains one or more
  physical devices.

  Each device group contains:
    :devices             - Vector of PhysicalDevice records in the group
    :subset-allocation   - Boolean indicating if subset allocation is supported

  Subset allocation allows memory to be allocated for a subset of devices in
  the group rather than all devices.

  Returns a vector of PhysicalDeviceGroup records.

  Example:
    (physical-device-groups instance)
    ;; => [#crinklywrappr.sandalphon.core.PhysicalDeviceGroup{...}]"
  [^Instance instance]
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [group-count-ptr (.mallocInt stack 1)
          ^VkInstance instance-handle (handle instance)]

      ;; First call: get group count
      (check-result (VK11/vkEnumeratePhysicalDeviceGroups instance-handle group-count-ptr nil))

      (let [group-count (.get group-count-ptr 0)]
        (when (pos? group-count)
          ;; Second call: get groups
          (let [group-props (VkPhysicalDeviceGroupProperties/malloc group-count stack)]
            (check-result (VK11/vkEnumeratePhysicalDeviceGroups instance-handle group-count-ptr group-props))

            ;; Convert to PhysicalDeviceGroup records
            (vec
             (for [i (range group-count)]
               (let [group (.get group-props i)
                     device-count (.physicalDeviceCount group)
                     devices-buffer (.physicalDevices group)
                     devices (vec
                              (for [j (range device-count)]
                                (let [vk-device-handle (VkPhysicalDevice. (.get devices-buffer j) instance-handle)
                                      {:keys [device-name device-type api-version]} (query-device-properties vk-device-handle stack)]
                                  (with-meta (->PhysicalDevice device-name device-type api-version)
                                    {:handle vk-device-handle}))))
                     subset-allocation (= (.subsetAllocation group) VK10/VK_TRUE)]
                 (->PhysicalDeviceGroup devices subset-allocation))))))))))

(defn device-extensions
  "Returns device extensions supported by a physical device.

  Device extensions are different from instance extensions - they are specific
  to each physical device (GPU) and must be queried individually. Different GPUs
  support different extensions.

  Device extensions must be enabled when creating a LogicalDevice and are required
  for features like:
  - VK_KHR_swapchain - Required for rendering to screen
  - VK_KHR_ray_tracing_pipeline - Ray tracing support
  - VK_EXT_mesh_shader - Mesh shader support
  - VK_KHR_acceleration_structure - Ray tracing acceleration structures

  Instance layers can also provide device extensions. Use multi-arity forms to
  query extensions from layers:

  Arities:
    (physical-device)           - Returns device's own extensions
    (physical-device layer)     - Returns extensions for a single layer (or device if layer is nil)
    (physical-device layer ...) - Returns union of extensions for all layers (excludes device unless nil passed)

  To union device extensions with layer extensions:
    (apply device-extensions physical-device nil [layer1 layer2])

  Returns a set of Extension records, each containing:
    :extension-name - Extension name (e.g., \"VK_KHR_swapchain\")
    :spec-version   - Extension specification version (raw integer)

  Examples:
    (device-extensions physical-device)                           ;; Device only
    (device-extensions physical-device validation-layer)          ;; Layer only
    (device-extensions physical-device layer1 layer2)             ;; Union of layers
    (apply device-extensions physical-device nil [layer1 layer2]) ;; Device + layers"
  ([physical-device]
   (with-open [^MemoryStack stack (MemoryStack/stackPush)]
     (query-device-extension-properties (handle physical-device) nil stack)))

  ([physical-device layer]
   (with-open [^MemoryStack stack (MemoryStack/stackPush)]
     (query-device-extension-properties (handle physical-device)
                                        (when layer (:layer-name layer))
                                        stack)))

  ([physical-device layer & layers]
   (with-open [^MemoryStack stack (MemoryStack/stackPush)]
     (transduce
      (map (fn [l] (query-device-extension-properties (handle physical-device)
                                                      (when l (:layer-name l))
                                                      stack)))
      st/union (cons layer layers)))))

(defn supported-features
  "Returns core device features supported by a physical device.

  Device features are optional functionality that must be queried and enabled.
  Unlike properties (which describe hardware limits), features are boolean flags
  indicating whether specific functionality is available.

  Features must be checked before use and enabled when creating a LogicalDevice:
  - Check what's supported using this function
  - Enable required features when creating LogicalDevice
  - Vulkan will fail if you try to use features without enabling them

  Common features:
  - :geometry-shader - Geometry shader stage support
  - :tessellation-shader - Tessellation shader stages support
  - :multi-draw-indirect - Multiple draws with single command
  - :sample-rate-shading - Per-sample shading
  - :dual-src-blend - Dual source color blending
  - :logic-op - Framebuffer logical operations

  Returns a set of feature keywords (kebab-case).

  TODO: This returns only CORE features (~55 features). Extension features
  (~355 additional features) require vkGetPhysicalDeviceFeatures2 and are
  not yet implemented.

  Example:
    (supported-features physical-device)
    ;; => #{:geometry-shader :tessellation-shader :multi-draw-indirect ...}

    ;; Check if geometry shader is supported
    (contains? (supported-features physical-device) :geometry-shader)
    ;; => true"
  [^PhysicalDevice physical-device]
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [^VkPhysicalDevice device-handle (handle physical-device)
          features (VkPhysicalDeviceFeatures/malloc stack)]

      ;; Query device features
      (VK10/vkGetPhysicalDeviceFeatures device-handle features)

      ;; Use reflection to get all feature fields (boolean getters)
      (let [feature-methods (->> (.getDeclaredMethods VkPhysicalDeviceFeatures)
                                 (filter (fn [^java.lang.reflect.Method m]
                                           (and (= (.getReturnType m) Boolean/TYPE)
                                                (zero? (count (.getParameterTypes m)))))))]

        ;; Filter enabled features and convert to kebab-case keywords
        (set
         (for [^java.lang.reflect.Method method feature-methods
               :let [feature-value (.invoke method features (into-array Object []))
                     field-name (.getName method)]
               :when feature-value] ;; true = enabled
           (csk/->kebab-case-keyword field-name)))))))

;; ============================================================================
;; Queues
;; ============================================================================

(defn- query-queue-family-properties
  "Queries queue family properties for a physical device.
  Returns a VkQueueFamilyProperties.Buffer containing all queue families."
  [^VkPhysicalDevice device-handle ^MemoryStack stack]
  (let [count-ptr (.mallocInt stack 1)]
    ;; First call: get family count
    (VK10/vkGetPhysicalDeviceQueueFamilyProperties device-handle count-ptr nil)
    (let [family-count (.get count-ptr 0)]
      (when (pos? family-count)
        ;; Second call: get family properties
        (let [family-props (VkQueueFamilyProperties/malloc family-count stack)]
          (VK10/vkGetPhysicalDeviceQueueFamilyProperties device-handle count-ptr family-props)
          family-props)))))

(defrecord QueueFamily [flags]
  PropertyQueryable
  (properties [this]
    (with-open [^MemoryStack stack (MemoryStack/stackPush)]
      (let [^VkPhysicalDevice device-handle (-> this meta :device-handle)
            family-index (index this)
            family-props (query-queue-family-properties device-handle stack)
            props (.get family-props family-index)]
        {:flags flags
         :max-queue-count (.queueCount props)
         :timestamp-valid-bits (.timestampValidBits props)
         :min-image-transfer-granularity {:width (.width (.minImageTransferGranularity props))
                                          :height (.height (.minImageTransferGranularity props))
                                          :depth (.depth (.minImageTransferGranularity props))}})))

  Indexable
  (index [this]
    (-> this meta :family-index)))

(defn- queue-flag->keyword
  "Converts a Vulkan queue flag bit to a keyword."
  [queue-flag]
  (condp = queue-flag
    VK10/VK_QUEUE_GRAPHICS_BIT :graphics
    VK10/VK_QUEUE_COMPUTE_BIT :compute
    VK10/VK_QUEUE_TRANSFER_BIT :transfer
    VK10/VK_QUEUE_SPARSE_BINDING_BIT :sparse-binding
    VK11/VK_QUEUE_PROTECTED_BIT :protected
    KHRVideoDecodeQueue/VK_QUEUE_VIDEO_DECODE_BIT_KHR :video-decode
    KHRVideoEncodeQueue/VK_QUEUE_VIDEO_ENCODE_BIT_KHR :video-encode
    NVOpticalFlow/VK_QUEUE_OPTICAL_FLOW_BIT_NV :optical-flow
    :other))

(defn- queue-flags->keywords
  "Extracts queue capability flags from a bitmask and returns a set of keywords."
  [queue-flags-bitmask]
  (let [flag-bits [VK10/VK_QUEUE_GRAPHICS_BIT
                   VK10/VK_QUEUE_COMPUTE_BIT
                   VK10/VK_QUEUE_TRANSFER_BIT
                   VK10/VK_QUEUE_SPARSE_BINDING_BIT
                   VK11/VK_QUEUE_PROTECTED_BIT
                   KHRVideoDecodeQueue/VK_QUEUE_VIDEO_DECODE_BIT_KHR
                   KHRVideoEncodeQueue/VK_QUEUE_VIDEO_ENCODE_BIT_KHR
                   NVOpticalFlow/VK_QUEUE_OPTICAL_FLOW_BIT_NV]]
    (set (for [bit flag-bits
               :when (not= 0 (bit-and queue-flags-bitmask bit))]
           (queue-flag->keyword bit)))))

(defn queue-families
  "Returns queue families for a PhysicalDevice.

  Each QueueFamily record contains:
    :flags - Set of capability keywords (:graphics, :compute, :transfer, :sparse-binding)
    :max-queue-count - Maximum number of queues available in this family

  Additional properties are available via (properties queue-family):
    :timestamp-valid-bits    - Number of valid bits for timestamp queries
    :min-image-transfer-granularity - Minimum granularity for image transfers

  Returns a vector of QueueFamily records.

  Example:
    (queue-families physical-device)
    ;; => [#crinklywrappr.sandalphon.core.QueueFamily{:flags #{:graphics :compute :transfer} :max-queue-count 1}]"
  [^PhysicalDevice physical-device]
  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [^VkPhysicalDevice device-handle (handle physical-device)
          family-props (query-queue-family-properties device-handle stack)]
      (when family-props
        ;; Convert to QueueFamily records
        (vec
         (for [i (range (.capacity family-props))]
           (let [props (.get family-props i)
                 queue-flags (.queueFlags props)
                 flags (queue-flags->keywords queue-flags)
                 max-queue-count (.queueCount props)]
             (with-meta (map->QueueFamily {:flags flags :max-queue-count max-queue-count})
               {:device-handle device-handle
                :family-index i}))))))))

;; queue-count is inferred from `(count priorities)`
(defrecord QueueBuilder [queue-family priorities]
  Indexable
  (index [_this] (index queue-family)))

(defn queue-builder [queue-family & {:keys [queue-count priorities]}]
  (if (seq priorities)
    (->QueueBuilder queue-family priorities)
    (->QueueBuilder queue-family (vec (repeat (or queue-count 1) 1.0))))) ;; default priority of 1.0

(defn resolve-queue-requests [xs]
  (reduce
   (fn [a b]
     (cond
       (instance? QueueBuilder b)
       (assoc a (index b) {:queue-count (count (:priorities b))
                           :queue-priorities (:priorities b)
                           :queue-family (:queue-family b)})

       (instance? QueueFamily b)
       (assoc a (index b) {:queue-count 1
                           :queue-priorities [1.0]
                           :queue-family b})

       :else a))
   {} xs))

(defn- create-queue-infos
  "Creates VkDeviceQueueCreateInfo structures for the given queue families.

  Parameters:
    queue-requests-map - Map of family-index to queue specs (from resolve-queue-requests)
                         Format: {index {:queue-count N :queue-priorities [...]}}
    stack - MemoryStack for allocation

  Returns VkDeviceQueueCreateInfo.Buffer"
  [queue-requests-map ^MemoryStack stack]
  (let [queue-create-infos (VkDeviceQueueCreateInfo/malloc (count queue-requests-map) stack)]
    (doseq [[idx [family-idx spec]] (map-indexed vector queue-requests-map)]
      (let [queue-info (.get queue-create-infos idx)
            queue-count (:queue-count spec)
            priorities (:queue-priorities spec)
            priority-buffer (.mallocFloat stack queue-count)]
        ;; Fill priority buffer
        (doseq [priority priorities]
          (.put priority-buffer (float priority)))
        (.flip priority-buffer)

        (.sType$Default queue-info)
        (.pNext queue-info 0)
        (.flags queue-info 0)
        (.queueFamilyIndex queue-info family-idx)
        (.pQueuePriorities queue-info priority-buffer)))
    queue-create-infos))

(defn- enable-features
  "Enables the specified features in a VkPhysicalDeviceFeatures struct.

  Uses reflection to find and invoke setter methods for each feature keyword.
  Feature keywords are converted from kebab-case to camelCase.

  Parameters:
    enabled-features - Set of feature keywords (e.g., #{:geometry-shader})
    stack - MemoryStack for allocation

  Returns VkPhysicalDeviceFeatures or nil if no features enabled"
  [enabled-features ^MemoryStack stack]
  (when (seq enabled-features)
    (let [features (VkPhysicalDeviceFeatures/calloc stack)]
      (doseq [feature-kw enabled-features]
        (let [field-name (csk/->camelCaseString feature-kw)
              methods (.getDeclaredMethods VkPhysicalDeviceFeatures)
              setter-method (first (filter (fn [^java.lang.reflect.Method m]
                                             (and (= (.getName m) field-name)
                                                  (= 1 (count (.getParameterTypes m)))
                                                  (= Boolean/TYPE (first (.getParameterTypes m)))))
                                           methods))]
          (when setter-method
            (.invoke ^java.lang.reflect.Method setter-method features
                     (into-array Object [true])))))
      features)))

(defn- create-device-group-info
  "Creates VkDeviceGroupDeviceCreateInfo for a physical device group.

  Parameters:
    physical-device-group - PhysicalDeviceGroup record
    stack - MemoryStack for allocation

  Returns VkDeviceGroupDeviceCreateInfo or nil if no group provided"
  [physical-device-group ^MemoryStack stack]
  (when physical-device-group
    (let [devices (:devices physical-device-group)
          device-count (count devices)
          device-handles (.mallocPointer stack device-count)]
      (doseq [[idx device] (map-indexed vector devices)]
        (.put device-handles idx (.address ^VkPhysicalDevice (handle device))))
      (-> (VkDeviceGroupDeviceCreateInfo/malloc stack)
          (.sType$Default)
          (.pNext 0)
          (.pPhysicalDevices device-handles)))))

(defrecord Queue [queue-family]
  IVulkanHandle
  (handle [this]
    (-> this meta :handle))

  Indexable
  (index [this]
    (-> this meta :queue-index)))

(defn- create-queues
  "Creates Queue records for a logical device.

  Parameters:
    vk-handle - VkDevice handle
    queue-requests-map - Map of family-index to queue specs
    stack - MemoryStack for allocation

  Returns a vector of Queue records with :handle and :queue-index in metadata"
  [^VkDevice vk-handle queue-requests-map ^MemoryStack stack]
  (vec
   (for [[family-idx spec] queue-requests-map
         :let [queue-family (:queue-family spec)
               queue-count (:queue-count spec)]
         queue-idx (range queue-count)]
     (let [queue-ptr (.mallocPointer stack 1)]
       (VK10/vkGetDeviceQueue vk-handle family-idx queue-idx queue-ptr)
       (let [vk-queue (VkQueue. (.get queue-ptr 0) vk-handle)]
         (with-meta (->Queue (dissoc queue-family :max-queue-count))
           {:handle vk-queue
            :queue-index queue-idx}))))))

;; ============================================================================
;; Memory Allocator
;; ============================================================================

(defrecord VulkanMemoryAllocator [flags]
  IVulkanHandle
  (handle [this]
    (-> this meta :handle))

  java.io.Closeable
  (close [this]
    (when-let [allocator-handle (handle this)]
      (Vma/vmaDestroyAllocator allocator-handle))))

(defn- flag-field? [prefix]
  (fn inner-flag-field? [member]
    (and (st/subset? #{:public :static :final} (:flags member))
         (.startsWith (name (:name member)) prefix))))

(def ^:private allocator-flag-map
  "Maps allocator flag keywords to VMA_ALLOCATOR_CREATE_* bit values.

  Automatically generated via reflection from the Vma class."
  (letfn [(mapping [member]
            (let [field-name (-> member :name name)
                  cleaned (-> field-name
                              (.substring 21) ; Remove "VMA_ALLOCATOR_CREATE_"
                              (sg/replace #"_BIT$" ""))
                  field-keyword (csk/->kebab-case-keyword cleaned)
                  field-value (.get (.getField Vma field-name) nil)]
              (when (and (seq cleaned) (number? field-value))
                [field-keyword field-value])))]
    (transduce
     (comp (filter (flag-field? "VMA_ALLOCATOR_CREATE_"))
           (keep mapping))
     (completing
      (fn f [a [k v]]
        (assoc a k v)))
     {} (:members (reflect/reflect Vma)))))

(defn allocator-flags
  "Returns a set of available allocator flag keywords.

  The flags are automatically discovered from the VMA library via reflection.

  Example:
    (allocator-flags)
    ;; => #{:externally-synchronized, :khr-dedicated-allocation, ...}"
  []
  (set (keys allocator-flag-map)))

(defn- flags->int
  "Converts a set of flag keywords to a combined integer flags value using bit-or.

  Takes a flag map and a set of keywords, returning the bitwise OR of all flag values."
  [flag-map flag-keywords]
  (if (empty? flag-keywords)
    0
    (transduce
     (map flag-map)
     (completing bit-or)
     0 flag-keywords)))

(defn- memory-allocator!
  "Creates a VulkanMemoryAllocator using VMA (Vulkan Memory Allocator).

  This allocator uses VMA's default Best-Fit algorithm with automatic size-tier
  optimization (small <4KB, medium 4KB-1MB, large >1MB allocations).

  VMA manages device memory allocation and suballocation automatically, handling:
  - Memory type selection based on usage requirements
  - Alignment constraints and buffer-image granularity
  - Fragmentation minimization
  - Memory pool management

  Arguments:
  - device-handle - VkDevice handle (required)
  - physical-device - PhysicalDevice record (required)
  - stack - MemoryStack for allocations (required)
  - flags - Set of allocator flag keywords (optional, defaults to #{})

  Returns a VulkanMemoryAllocator with VMA handle in metadata.

  Note on Physical Device vs Logical Device:
  VMA requires both the logical device (VkDevice) and physical device (VkPhysicalDevice):
  - Logical device - Used for executing memory operations (vkAllocateMemory, vkFreeMemory)
  - Physical device - Used for querying memory capabilities (memory types, heaps, limits)

  The VkDevice handle doesn't provide reverse lookup to its physical device, so VMA
  needs both handles explicitly. For device groups, the physical device from
  LogicalDevice represents the group's shared memory architecture (all devices in
  a group must have compatible memory properties)."
  [^VkDevice device-handle physical-device ^MemoryStack stack & {:keys [flags] :or {flags #{}}}]
  (let [^VkPhysicalDevice phys-dev-handle (handle physical-device)
        ^VkInstance instance-handle (-> physical-device meta :instance handle)

        ;; Get Vulkan API version (create-vulkan-version handles nil, defaults to 1.0.0)
        api-version (pack (:api-version physical-device))

        ;; Convert flag keywords to integer
        flags-int (flags->int allocator-flag-map flags)

        ;; Setup Vulkan function pointers
        vk-functions (doto (VmaVulkanFunctions/calloc stack)
                       (.set instance-handle device-handle))

        allocator-info (-> (VmaAllocatorCreateInfo/calloc stack)
                           (.flags flags-int)
                           (.physicalDevice phys-dev-handle)
                           (.device device-handle)
                           (.pVulkanFunctions vk-functions)
                           (.instance instance-handle)
                           (.vulkanApiVersion api-version))

        allocator-ptr (.mallocPointer stack 1)]

    (check-result (Vma/vmaCreateAllocator allocator-info allocator-ptr))

    (let [vma-handle (.get allocator-ptr 0)]
      (with-meta (->VulkanMemoryAllocator flags)
        {:handle vma-handle}))))

;; ============================================================================
;; Logical Device Creation
;; ============================================================================

(defrecord LogicalDevice [queues physical-device allocator]
  java.io.Closeable
  (close [this]
    (when allocator
      (.close allocator))
    (when-let [^VkDevice vk-handle (handle this)]
      (VK10/vkDestroyDevice vk-handle nil)))

  IVulkanHandle
  (handle [this]
    (-> this meta :handle)))

(defn logical-device!
  "Creates a Vulkan logical device.

  A logical device is a connection to a physical device (GPU) that allows you to
  create resources (buffers, images, pipelines) and submit work to queues.

  The logical device must be destroyed when no longer needed. Use with-open for
  automatic cleanup.

  Required parameters (at least one):
    :physical-device       - PhysicalDevice to create device from
    :physical-device-group - PhysicalDeviceGroup for multi-GPU

  When both :physical-device and :physical-device-group are provided, the physical
  device must be a member of the device group.

  Required parameters:
    :queue-requests - Heterogenous sequence of QueueFamily and/or QueueBuilder records

                      Can contain:
                      - QueueFamily records (creates 1 queue with priority 1.0)
                      - QueueBuilder records (from queue-builder function)

                      Duplicate queue families are merged such that one of them will win.
                      Queue count must not exceed (:max-queue-count queue-family).

  Optional parameters:
    :enabled-extensions - Vector of Extension records to enable
    :enabled-features   - Set of feature keywords to enable (e.g., #{:geometry-shader})
    :allocator-flags    - Set of allocator flag keywords (e.g., #{:ext-memory-budget})
                          Call (allocator-flags) to see all available flags.
                          Defaults to empty set (thread-safe, automatic extension detection)

  Returns a LogicalDevice record with the VkDevice handle stored in metadata.

  Examples:
    ;; Simple: Use first available queue family
    (with-open [device (logical-device!
                        :physical-device physical-device
                        :queue-requests (take 1 (queue-families physical-device)))]
      (do-work device))

    ;; All families with defaults
    (with-open [device (logical-device!
                        :physical-device physical-device
                        :queue-requests (queue-families physical-device))]
      (do-work device))

    ;; Customize specific families
    (let [families (queue-families physical-device)
          graphics (first (filter #(contains? (:flags %) :graphics) families))]
      (with-open [device (logical-device!
                          :physical-device physical-device
                          :queue-requests [(queue-builder graphics :queue-count 2)
                                          (second families)]  ; default for second family
                          :enabled-features #{:geometry-shader})]
        (do-work device)))

  Throws:
    - ExceptionInfo if validation fails or device creation fails"
  [& {:keys [physical-device
             physical-device-group
             queue-requests
             enabled-extensions
             enabled-features
             allocator-flags]}]

  ;; Validation
  (when-not (or physical-device physical-device-group)
    (throw (ex-info "Must provide either :physical-device or :physical-device-group"
                    {:physical-device physical-device
                     :physical-device-group physical-device-group})))

  (when-not queue-requests
    (throw (ex-info "Must provide :queue-requests"
                    {:queue-requests queue-requests})))

  (when (and physical-device physical-device-group)
    (when-not (some #(= physical-device %) (:devices physical-device-group))
      (throw (ex-info ":physical-device must be a member of :physical-device-group"
                      {:physical-device physical-device
                       :physical-device-group physical-device-group}))))

  ;; Resolve queue requests to index -> config map
  (let [queue-requests-map (resolve-queue-requests queue-requests)]

    ;; Validate queue-count doesn't exceed max-queue-count
    (doseq [[family-idx spec] queue-requests-map]
      (let [requested-count (:queue-count spec)
            max-count (-> spec :queue-family :max-queue-count)]
        (when (> requested-count max-count)
          (throw (ex-info "Requested queue-count exceeds max-queue-count for queue family"
                          {:family-index family-idx
                           :requested-count requested-count
                           :max-queue-count max-count})))))

    (with-open [^MemoryStack stack (MemoryStack/stackPush)]
      (let [physical-device' (or physical-device (first (:devices physical-device-group)))
            phys-dev-handle (handle physical-device')
            device-group-info (create-device-group-info physical-device-group stack)
            queue-create-infos (create-queue-infos queue-requests-map stack)
            extension-names (when (seq enabled-extensions)
                              (strings->pointer-buffer enabled-extensions :extension-name stack))
            enabled-features-struct (enable-features enabled-features stack)
            device-info (-> (VkDeviceCreateInfo/malloc stack)
                            (.sType$Default)
                            (.pNext (if device-group-info (.address device-group-info) 0))
                            (.flags 0)
                            (.pQueueCreateInfos queue-create-infos)
                            (.ppEnabledExtensionNames extension-names)
                            (.pEnabledFeatures enabled-features-struct))
            device-ptr (.mallocPointer stack 1)]

        (check-result (VK10/vkCreateDevice phys-dev-handle device-info nil device-ptr))

        (let [vk-handle (VkDevice. (.get device-ptr 0) phys-dev-handle device-info)
              queues (create-queues vk-handle queue-requests-map stack)
              allocator (memory-allocator! vk-handle physical-device' stack :flags (or allocator-flags #{}))
              config (cond-> {:queues queues
                              :physical-device physical-device'
                              :allocator allocator}
                       physical-device-group (assoc :physical-device-group physical-device-group)
                       (seq enabled-extensions) (assoc :enabled-extensions (vec enabled-extensions))
                       (seq enabled-features) (assoc :enabled-features (set enabled-features)))]
          (with-meta (map->LogicalDevice config) {:handle vk-handle}))))))

;; ============================================================================
;; Buffer Creation - Flags and Enums
;; ============================================================================

;; NOTE FOR MAINTAINERS: The deprecated memory usage values below are based on
;; VMA 3.0 documentation. When updating LWJGL/VMA versions, check the VMA changelog
;; for newly deprecated values and update this set accordingly.
(def ^:private deprecated-memory-usages
  "Set of deprecated VMA memory usage keywords (VMA 3.0+).

  These values still work but VMA recommends using :auto, :auto-prefer-device,
  or :auto-prefer-host instead."
  #{:gpu-only :cpu-only :cpu-to-gpu :gpu-to-cpu :cpu-copy :gpu-lazily-allocated})

(def ^:private buffer-usage-flag-map
  "Maps buffer usage flag keywords to VK_BUFFER_USAGE_* bit values.

  Automatically generated via reflection from the VK10 class."
  (letfn [(mapping [member]
            (let [field-name (-> member :name name)
                  cleaned (-> field-name
                              (.substring 16) ; Remove "VK_BUFFER_USAGE_"
                              (sg/replace #"_BIT$" ""))
                  field-keyword (csk/->kebab-case-keyword cleaned)
                  field-value (.get (.getField VK10 field-name) nil)]
              (when (and (seq cleaned) (number? field-value))
                [field-keyword field-value])))]
    (transduce
     (comp (filter (flag-field? "VK_BUFFER_USAGE_"))
        (keep mapping))
     (completing
      (fn f [a [k v]]
        (assoc a k v)))
     {} (:members (reflect/reflect VK10)))))

(def ^:private allocation-flag-map
  "Maps VMA allocation flag keywords to VMA_ALLOCATION_CREATE_* bit values.

  Automatically generated via reflection from the Vma class.
  Excludes STRATEGY_MASK which is not a usable flag."
  (letfn [(allocation-flag-field? [member]
            (not= (name (:name member)) "VMA_ALLOCATION_CREATE_STRATEGY_MASK"))
          (mapping [member]
            (let [field-name (-> member :name name)
                  cleaned (-> field-name
                              (.substring 22) ; Remove "VMA_ALLOCATION_CREATE_"
                              (sg/replace #"_BIT$" ""))
                  field-keyword (csk/->kebab-case-keyword cleaned)
                  field-value (.get (.getField Vma field-name) nil)]
              (when (and (seq cleaned) (number? field-value))
                [field-keyword field-value])))]
    (transduce
     (comp (filter (every-pred (flag-field? "VMA_ALLOCATION_CREATE_")
                               allocation-flag-field?))
           (keep mapping))
     (completing
      (fn f [a [k v]]
        (assoc a k v)))
     {} (:members (reflect/reflect Vma)))))

(def ^:private memory-usage-map
  "Maps VMA memory usage keywords to VMA_MEMORY_USAGE_* enum values.

  Automatically generated via reflection from the Vma class.

  Use :auto, :auto-prefer-device, or :auto-prefer-host for new code."
  (letfn [(mapping [member]
            (let [field-name (-> member :name name)
                  cleaned (.substring field-name 17) ; Remove "VMA_MEMORY_USAGE_"
                  field-keyword (csk/->kebab-case-keyword cleaned)
                  field-value (.get (.getField Vma field-name) nil)]
              (when (and (seq cleaned) (number? field-value))
                [field-keyword field-value])))]
    (transduce
     (comp (filter (flag-field? "VMA_MEMORY_USAGE_"))
           (keep mapping))
     (completing
      (fn f [a [k v]]
        (assoc a k v)))
     {} (:members (reflect/reflect Vma)))))

(defn buffer-usage-flags
  "Returns a set of available buffer usage flag keywords.

  The flags are automatically discovered from Vulkan via reflection.

  Example:
    (buffer-usage-flags)
    ;; => #{:vertex-buffer, :index-buffer, :uniform-buffer, ...}"
  []
  (set (keys buffer-usage-flag-map)))

(defn allocation-flags
  "Returns a set of available VMA allocation flag keywords.

  The flags are automatically discovered from VMA via reflection.

  Example:
    (allocation-flags)
    ;; => #{:mapped, :dedicated-memory, :host-access-sequential-write, ...}"
  []
  (set (keys allocation-flag-map)))

(defn memory-usages
  "Returns a set of available VMA memory usage keywords.

  Excludes deprecated values (VMA 3.0+).
  Use :auto (recommended), :auto-prefer-device, or :auto-prefer-host.

  Example:
    (memory-usages)
    ;; => #{:auto, :auto-prefer-device, :auto-prefer-host, :unknown}"
  []
  (transduce
   (remove #(contains? deprecated-memory-usages %))
   conj #{} (keys memory-usage-map)))

(defn- memory-usage->int
  "Converts a memory usage keyword to its integer value."
  [usage-keyword]
  (or (memory-usage-map usage-keyword)
      (throw (ex-info "Invalid memory usage keyword"
                      {:usage usage-keyword
                       :valid-usages (memory-usages)}))))

;; ============================================================================
;; Buffer Creation
;; ============================================================================

(defn- validate-flag-keywords
  "Validates that all keywords in a set exist in the given flag map.

  Throws ExceptionInfo if any invalid keywords are found."
  [flag-map flag-keywords param-name valid-fn-name]
  (let [valid-keys (set (keys flag-map))
        invalid-keys (remove valid-keys flag-keywords)]
    (when (seq invalid-keys)
      (throw (ex-info (str "Invalid " param-name " keywords")
                      {:invalid-keywords invalid-keys
                       :valid-keywords valid-keys
                       :hint (str "Call (" valid-fn-name ") to see available options")})))))

(defrecord Buffer [size usage memory-usage allocation-flags]
  IVulkanHandle
  (handle [this]
    (-> this meta :handle))

  java.io.Closeable
  (close [this]
    (let [buffer-handle (handle this)
          allocator (-> this meta :allocator)
          allocation (-> this meta :allocation)]
      (when (and buffer-handle allocator allocation)
        (Vma/vmaDestroyBuffer allocator buffer-handle allocation)))))

(defn memory-buffer!
  "Creates a Vulkan buffer with automatic memory allocation via VMA.

  A buffer is a linear array of data stored in GPU memory. Buffers are used for
  vertex data, indices, uniforms, storage, and data transfer operations.

  The buffer must be destroyed when no longer needed. Use with-open for automatic cleanup.

  Required parameters:
    :device        - LogicalDevice that owns this buffer
    :size          - Size of buffer in bytes (must be > 0)
    :usage         - Set of buffer usage flags (e.g., #{:vertex-buffer :transfer-dst})
                     Call (buffer-usage-flags) to see all available flags

  Optional parameters:
    :memory-usage      - Memory usage hint for VMA (default: :auto)
                         Call (memory-usages) to see available options
                         Deprecated flags are accepted but will not be shown
    :allocation-flags  - Set of VMA allocation flags (default: #{})
                         Call (allocation-flags) to see available flags
    :queue-families    - Seq of QueueFamily records for concurrent access (default: [])
                         Empty/nil = exclusive mode (single queue family)
                         Non-empty = concurrent mode (multiple queue families can access)

  Returns a Buffer record with VkBuffer and VmaAllocation handles in metadata.

  Examples:
    ;; Simple vertex buffer (exclusive mode, default)
    (with-open [buffer (buffer! device
                                :size 1024
                                :usage #{:vertex-buffer})]
      (upload-data buffer vertices))

    ;; Staging buffer for transfers
    (with-open [staging (buffer! device
                                 :size (* 1024 1024)
                                 :usage #{:transfer-src}
                                 :memory-usage :auto-prefer-host
                                 :allocation-flags #{:mapped})]
      (copy-to-device staging data))

    ;; Uniform buffer with host access
    (with-open [uniform (buffer! device
                                 :size 256
                                 :usage #{:uniform-buffer}
                                 :allocation-flags #{:mapped :host-access-sequential-write})]
      (update-uniforms uniform camera-matrix))

    ;; Concurrent buffer accessible from multiple queue families
    (let [[graphics-family transfer-family] (queue-families physical-device)]
      (with-open [buffer (buffer! device
                                  :size 1024
                                  :usage #{:vertex-buffer :transfer-dst}
                                  :queue-families [graphics-family transfer-family])]
        (use-from-multiple-queues buffer)))

  Throws:
    - ExceptionInfo if validation fails or buffer creation fails"
  [device & {:keys [size usage memory-usage allocation-flags queue-families]
             :or {memory-usage :auto
                  allocation-flags #{}
                  queue-families []}}]

  ;; Validation
  (when-not (pos? size)
    (throw (ex-info "Buffer size must be positive"
                    {:size size})))

  (when (empty? usage)
    (throw (ex-info "Buffer usage must not be empty"
                    {:usage usage
                     :hint "Call (buffer-usage-flags) to see available flags"})))

  (validate-flag-keywords buffer-usage-flag-map usage "usage" "buffer-usage-flags")
  (validate-flag-keywords allocation-flag-map allocation-flags "allocation-flags" "allocation-flags")

  ;; Validate that queue families have queues on the device
  (when (seq queue-families)
    (let [device-queue-family-indices (set (map (comp index :queue-family) (:queues device)))
          requested-indices (map index queue-families)
          invalid-indices (remove device-queue-family-indices requested-indices)]
      (when (seq invalid-indices)
        (throw (ex-info "Queue families specified for buffer do not have queues on the device"
                        {:invalid-queue-family-indices invalid-indices
                         :device-queue-family-indices device-queue-family-indices
                         :hint "Only specify queue families that were included in :queue-requests when creating the device"})))))

  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [allocator (:allocator device)
          allocator-handle (handle allocator)

          ;; Determine sharing mode and extract queue family indices
          concurrent? (seq queue-families)
          queue-indices (when concurrent?
                          (vec (distinct (map index queue-families))))

          ;; Convert keywords to integers
          usage-flags (flags->int buffer-usage-flag-map usage)
          alloc-flags (flags->int allocation-flag-map allocation-flags)
          memory-usage-int (memory-usage->int memory-usage)
          sharing-mode-int (if concurrent?
                             VK10/VK_SHARING_MODE_CONCURRENT
                             VK10/VK_SHARING_MODE_EXCLUSIVE)

          ;; Setup queue family indices for concurrent mode
          queue-indices-buf (when concurrent?
                              (let [buf (.mallocInt stack (count queue-indices))]
                                (doseq [idx queue-indices]
                                  (.put buf (int idx)))
                                (.flip buf)))

          ;; Create buffer info
          buffer-info (-> (VkBufferCreateInfo/calloc stack)
                          (.sType$Default)
                          (.size size)
                          (.usage usage-flags)
                          (.sharingMode sharing-mode-int))

          ;; Set queue family indices if concurrent
          _ (when queue-indices-buf
              (.pQueueFamilyIndices buffer-info queue-indices-buf))

          ;; Create allocation info
          alloc-info (-> (VmaAllocationCreateInfo/calloc stack)
                         (.flags alloc-flags)
                         (.usage memory-usage-int))

          ;; Output pointers
          buffer-ptr (.mallocLong stack 1)
          allocation-ptr (.mallocPointer stack 1)]

      (check-result (Vma/vmaCreateBuffer allocator-handle buffer-info alloc-info
                                         buffer-ptr allocation-ptr nil))

      (let [buffer-handle (.get buffer-ptr 0)
            allocation-handle (.get allocation-ptr 0)]
        (with-meta (->Buffer size usage memory-usage allocation-flags)
          {:handle buffer-handle
           :allocation allocation-handle
           :allocator allocator-handle})))))

