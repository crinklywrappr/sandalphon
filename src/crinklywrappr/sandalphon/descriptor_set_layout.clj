(ns crinklywrappr.sandalphon.descriptor-set-layout
  "Builder functions for creating Vulkan descriptor set layouts.

  Three usage styles are supported:

  Builder style (threading macro):
    (-> (layout)
        (binding 0 :uniform-buffer #{:vertex :fragment})
        (binding 1 :sampled-image #{:fragment} :count 4)
        (flags #{:update-after-bind-pool})
        (build! device))

  Data literal style:
    (build! device
      {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment}}
                  {:binding 1 :type :sampled-image :stages #{:fragment} :count 4}]})

  Hybrid style (build bindings separately):
    (def camera (binding 0 :uniform-buffer #{:vertex :fragment}))
    (def textures (binding 1 :sampled-image #{:fragment} :count 4))
    (build! device {:bindings [camera textures]})"
  (:require [clojure.set :as st]
            [clojure.string :as sg]
            [clojure.reflect :as reflect]
            [camel-snake-kebab.core :as csk]
            [crinklywrappr.sandalphon.error :refer [check-result]]
            [crinklywrappr.sandalphon.protocols :refer [IVulkanHandle handle]])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan VK10 VK12
            VkDescriptorSetLayoutBinding
            VkDescriptorSetLayoutCreateInfo]
           [java.io Closeable]))

;; ============================================================================
;; Constants (discovered via reflection)
;; ============================================================================

(def ^:private descriptor-type->int
  "Map of descriptor type keywords to VK10 integer constants.
   Discovered via reflection at load time."
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "VK_DESCRIPTOR_TYPE_"))
           (keep (fn [s]
                   (when-let [v (.get (.getField VK10 s) nil)]
                     [(-> (sg/replace s "VK_DESCRIPTOR_TYPE_" "")
                          csk/->kebab-case-keyword)
                      v]))))
          (:members (reflect/type-reflect VK10)))))

(def ^:private int->descriptor-type
  "Reverse mapping: VK10 integer constants to descriptor type keywords."
  (delay (st/map-invert @descriptor-type->int)))

(def ^:private stage->int
  "Map of shader stage keywords to VK10 bit constants.
   Discovered via reflection at load time."
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "VK_SHADER_STAGE_"))
           (filter #(sg/ends-with? % "_BIT"))
           (keep (fn [s]
                   (when-let [v (.get (.getField VK10 s) nil)]
                     [(-> (sg/replace s "VK_SHADER_STAGE_" "")
                          (sg/replace "_BIT" "")
                          csk/->kebab-case-keyword)
                      v]))))
          (:members (reflect/type-reflect VK10)))))

(def ^:private int->stage
  "Reverse mapping: VK10 bit constants to shader stage keywords."
  (delay (st/map-invert @stage->int)))

(def ^:private binding-flag->int
  "Map of descriptor binding flag keywords to VK12 bit constants.
   Discovered via reflection at load time."
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "VK_DESCRIPTOR_BINDING_"))
           (filter #(sg/ends-with? % "_BIT"))
           (keep (fn [s]
                   (when-let [v (.get (.getField VK12 s) nil)]
                     [(-> (sg/replace s "VK_DESCRIPTOR_BINDING_" "")
                          (sg/replace "_BIT" "")
                          csk/->kebab-case-keyword)
                      v]))))
          (:members (reflect/type-reflect VK12)))))

(def ^:private int->binding-flag
  "Reverse mapping: VK12 bit constants to descriptor binding flag keywords."
  (delay (st/map-invert @binding-flag->int)))

(def ^:private layout-flag->int
  "Map of layout create flag keywords to VK12 bit constants.
   Discovered via reflection at load time."
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "VK_DESCRIPTOR_SET_LAYOUT_CREATE_"))
           (filter #(sg/ends-with? % "_BIT"))
           (keep (fn [s]
                   (when-let [v (.get (.getField VK12 s) nil)]
                     [(-> (sg/replace s "VK_DESCRIPTOR_SET_LAYOUT_CREATE_" "")
                          (sg/replace "_BIT" "")
                          csk/->kebab-case-keyword)
                      v]))))
          (:members (reflect/type-reflect VK12)))))

(def ^:private int->layout-flag
  "Reverse mapping: Vulkan bit constants to layout create flag keywords."
  (delay (st/map-invert @layout-flag->int)))

;; ============================================================================
;; Public Query Functions
;; ============================================================================

(defn descriptor-types
  "Returns the set of supported descriptor type keywords.

   Example types:
     :uniform-buffer          - Small read-only data (matrices, parameters)
     :storage-buffer          - Large or read-write data
     :uniform-buffer-dynamic  - Uniform buffer with runtime offset
     :storage-buffer-dynamic  - Storage buffer with runtime offset
     :sampled-image           - Textures for sampling
     :storage-image           - Images for compute read/write
     :sampler                 - Separate sampler object
     :combined-image-sampler  - Texture + sampler together
     :input-attachment        - Framebuffer input for subpasses"
  []
  (set (keys @descriptor-type->int)))

(defn shader-stages
  "Returns the set of supported shader stage keywords.

   Example stages:
     :vertex                   - Vertex shader
     :fragment                 - Fragment shader
     :compute                  - Compute shader
     :geometry                 - Geometry shader
     :tessellation-control     - Tessellation control shader
     :tessellation-evaluation  - Tessellation evaluation shader"
  []
  (set (keys @stage->int)))

(defn binding-flags
  "Returns the set of supported descriptor binding flag keywords.

   Flags:
     :update-after-bind             - Descriptors can be updated after binding to command buffer
     :partially-bound               - Not all array elements need valid descriptors
     :update-unused-while-pending   - Can update descriptors not used by pending commands
     :variable-descriptor-count     - Final binding has runtime-determined array size"
  []
  (set (keys @binding-flag->int)))

(defn layout-flags
  "Returns the set of supported descriptor set layout create flag keywords.

   Flags:
     :update-after-bind-pool  - Descriptor sets from this layout can use update-after-bind"
  []
  (set (keys @layout-flag->int)))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn- validate-binding-number [n]
  (when-not (and (integer? n) (>= n 0))
    (throw (ex-info "Binding number must be a non-negative integer"
                    {:binding n})))
  n)

(defn- validate-descriptor-type [t]
  (when-not (contains? (descriptor-types) t)
    (throw (ex-info "Invalid descriptor type"
                    {:type t
                     :valid-types (descriptor-types)})))
  t)

(defn- validate-stages [stages]
  (when-not (and (set? stages) (seq stages))
    (throw (ex-info "Stages must be a non-empty set"
                    {:stages stages})))
  (let [valid (shader-stages)
        invalid (remove valid stages)]
    (when (seq invalid)
      (throw (ex-info "Invalid shader stages"
                      {:invalid-stages (set invalid)
                       :valid-stages valid}))))
  stages)

(defn- validate-count [c]
  (when-not (and (integer? c) (pos? c))
    (throw (ex-info "Count must be a positive integer"
                    {:count c})))
  c)

(defn- validate-binding-flags [flags]
  (when flags
    (when-not (set? flags)
      (throw (ex-info "Binding flags must be a set"
                      {:flags flags})))
    (let [valid (binding-flags)
          invalid (remove valid flags)]
      (when (seq invalid)
        (throw (ex-info "Invalid binding flags"
                        {:invalid-flags (set invalid)
                         :valid-flags valid})))))
  flags)

;; ============================================================================
;; Builder Functions
;; ============================================================================

(defn- validate-binding-map
  "Validates a binding map from data literal style."
  [{:keys [binding type stages count flags] :or {count 1} :as m}]
  (when-not (contains? m :binding)
    (throw (ex-info "Binding map missing :binding" {:binding-map m})))
  (when-not (contains? m :type)
    (throw (ex-info "Binding map missing :type" {:binding-map m})))
  (when-not (contains? m :stages)
    (throw (ex-info "Binding map missing :stages" {:binding-map m})))
  (validate-binding-number binding)
  (validate-descriptor-type type)
  (validate-stages stages)
  (validate-count count)
  (validate-binding-flags flags))

(defn- validate-layout-flags
  "Validates layout-level flags."
  [flag-set]
  (when flag-set
    (when-not (set? flag-set)
      (throw (ex-info "Layout flags must be a set" {:flags flag-set})))
    (let [valid (layout-flags)
          invalid (remove valid flag-set)]
      (when (seq invalid)
        (throw (ex-info "Invalid layout flags"
                        {:invalid-flags (set invalid)
                         :valid-flags valid}))))))

(defn layout
  "Creates an empty descriptor set layout builder map.

  With no arguments, returns a new empty layout:
    (layout) ;; => {:bindings []}

  With an existing map, validates and returns it:
    (layout {:bindings [...]}) ;; validates and returns"
  ([]
   {:bindings []})
  ([m]
   (when-not (map? m)
     (throw (ex-info "Layout must be a map" {:got (type m)})))
   (when-not (vector? (:bindings m))
     (throw (ex-info "Layout :bindings must be a vector"
                     {:bindings (:bindings m)})))
   (doseq [b (:bindings m)]
     (validate-binding-map b))
   (validate-layout-flags (:flags m))
   m))

(defn binding
  "Creates a descriptor binding or adds one to an existing layout.

  Without a layout (returns binding map):
    (binding 0 :uniform-buffer #{:vertex :fragment})
    (binding 1 :sampled-image #{:fragment} :count 4 :flags #{:partially-bound})

  With a layout (returns updated layout):
    (-> (layout)
        (binding 0 :uniform-buffer #{:vertex :fragment})
        (binding 1 :sampled-image #{:fragment}))

  Options:
    :count        - Number of descriptors (default 1, use for arrays)
    :flags        - Set of per-binding flags (e.g., #{:partially-bound})
    :samplers     - Vector of immutable sampler handles
    :binding-name - Debug name (not sent to Vulkan, for documentation)"
  {:arglists '([binding-num type stages & {:keys [count flags samplers binding-name]}]
               [layout binding-num type stages & {:keys [count flags samplers binding-name]}])}
  [& args]
  (let [[layout? binding-num type stages & opts]
        (if (map? (first args))
          args
          (cons nil args))
        {:keys [count flags samplers binding-name]
         :or {count 1}} (apply hash-map opts)

        ;; Validate & build binding map
        binding-map (cond-> {:binding (validate-binding-number binding-num)
                             :type (validate-descriptor-type type)
                             :stages (validate-stages stages)
                             :count (validate-count count)}
                      flags (assoc :flags (validate-binding-flags flags))
                      samplers (assoc :samplers samplers)
                      binding-name (assoc :binding-name binding-name))]

    (if layout?
      (update layout? :bindings conj binding-map)
      binding-map)))

(defn flags
  "Sets layout-level create flags on a descriptor set layout.

  Layout flags are different from per-binding flags:
    :update-after-bind-pool - Descriptor sets from this layout can use update-after-bind

  Example:
    (-> (layout)
        (binding 0 :uniform-buffer #{:vertex})
        (flags #{:update-after-bind-pool}))"
  [layout flag-set]
  (when-not (set? flag-set)
    (throw (ex-info "Flags must be a set" {:flags flag-set})))
  (validate-layout-flags flag-set)
  (assoc layout :flags flag-set))

;; ============================================================================
;; Layout Creation
;; ============================================================================

(defn- stages->int
  "Converts a set of stage keywords to a combined integer using bit-or."
  [stages]
  (transduce (map @stage->int) (completing bit-or) 0 stages))

(defn- layout-flags->int
  "Converts a set of layout flag keywords to a combined integer using bit-or."
  [flags]
  (if (empty? flags)
    0
    (transduce (map @layout-flag->int) (completing bit-or) 0 flags)))

(defrecord DescriptorSetLayout [layout-map]
  IVulkanHandle
  (handle [this] (:handle (meta this)))

  Closeable
  (close [this]
    (when-let [h (handle this)]
      (let [device (:device (meta this))
            device-handle (handle device)]
        (VK10/vkDestroyDescriptorSetLayout device-handle h nil)))))

(defn build!
  "Builds a VkDescriptorSetLayout from a layout map.

  Takes a device and a layout map (from builder or data literal).
  Returns a DescriptorSetLayout record implementing IVulkanHandle and Closeable.

  Example:
    (build! device
      {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment}}]})

  The returned layout can be closed with (.close layout) or used with with-open."
  [device layout-map]
  ;; Validate the layout map
  (layout layout-map)

  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [device-handle (handle device)
          bindings (:bindings layout-map)
          binding-count (count bindings)

          ;; Create binding structs
          binding-buffer (VkDescriptorSetLayoutBinding/calloc binding-count stack)
          _ (doseq [[idx b] (map-indexed vector bindings)]
              (let [binding-struct (.get binding-buffer idx)]
                (-> binding-struct
                    (.binding (:binding b))
                    (.descriptorType (get @descriptor-type->int (:type b)))
                    (.descriptorCount (or (:count b) 1))
                    (.stageFlags (stages->int (:stages b))))))

          ;; Create layout info
          layout-info (-> (VkDescriptorSetLayoutCreateInfo/calloc stack)
                          (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                          (.pBindings binding-buffer)
                          (.flags (layout-flags->int (or (:flags layout-map) #{}))))

          ;; Output pointer
          layout-ptr (.mallocLong stack 1)]

      (check-result (VK10/vkCreateDescriptorSetLayout device-handle layout-info nil layout-ptr))

      (let [layout-handle (.get layout-ptr 0)]
        (with-meta (->DescriptorSetLayout layout-map)
          {:handle layout-handle
           :device device})))))
