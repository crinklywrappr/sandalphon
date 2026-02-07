(ns crinklywrappr.sandalphon.descriptor-set-layout
  "Builder functions for creating Vulkan descriptor set layouts.

  Three usage styles are supported:

  Builder style (threading macro):
    (-> (layout)
        (descriptor 0 :uniform-buffer #{:vertex :fragment})
        (descriptor 1 :sampled-image #{:fragment} :count 4)
        (flags #{:update-after-bind-pool})
        (build! device))

  Data literal style:
    (build! device
      {:descriptors {0 {:type :uniform-buffer :stages #{:vertex :fragment}}
                  1 {:type :sampled-image :stages #{:fragment} :count 4}}})

  Hybrid style (build bindings separately):
    (def camera (descriptor 0 :uniform-buffer #{:vertex :fragment}))
    (def textures (descriptor 1 :sampled-image #{:fragment} :count 4))
    (build! device {:descriptors (into {} (map-indexed vector [camera textures]))})"
  (:require [crinklywrappr.sandalphon.constants :as const]
            [crinklywrappr.sandalphon.error :refer [check-result]]
            [crinklywrappr.sandalphon.protocols :refer [IVulkanHandle handle]])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan VK10
            VkDescriptorSetLayoutBinding
            VkDescriptorSetLayoutCreateInfo]
           [java.io Closeable]))

;; ============================================================================
;; Query Functions
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
  (set (keys @const/descriptor-types)))

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
  (set (keys @const/shader-stages)))

(defn descriptor-flags
  "Returns the set of supported descriptor binding flag keywords.

   Flags:
     :update-after-bind             - Descriptors can be updated after binding to command buffer
     :partially-bound               - Not all array elements need valid descriptors
     :update-unused-while-pending   - Can update descriptors not used by pending commands
     :variable-descriptor-count     - Final binding has runtime-determined array size"
  []
  (set (keys @const/binding-flags)))

(defn layout-flags
  "Returns the set of supported descriptor set layout create flag keywords.

   Flags:
     :update-after-bind-pool  - Descriptor sets from this layout can use update-after-bind"
  []
  (set (keys @const/layout-flags)))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn- validate-descriptor-index [n]
  (when-not (and (integer? n) (>= n 0))
    (throw (ex-info "Binding number must be a non-negative integer"
                    {:binding n})))
  n)

(defn- validate-descriptor-type [t]
  (when-not (contains? @const/descriptor-types t)
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

(defn- validate-descriptor-count [c]
  (when-not (and (integer? c) (pos? c))
    (throw (ex-info "Count must be a positive integer"
                    {:count c})))
  c)

(defn- validate-descriptor-flags [desc-flags desc-type]
  (when desc-flags
    (when-not (set? desc-flags)
      (throw (ex-info "Descriptor flags must be a set"
                      {:flags desc-flags})))
    (let [valid (descriptor-flags)
          invalid (remove valid desc-flags)]
      (when (seq invalid)
        (throw (ex-info "Invalid descriptor flags"
                        {:invalid-flags (set invalid)
                         :valid-flags valid}))))
    ;; Type-specific restrictions
    (when (and (contains? desc-flags :variable-descriptor-count)
               (contains? #{:uniform-buffer-dynamic :storage-buffer-dynamic} desc-type))
      (throw (ex-info "Variable descriptor count cannot be used with dynamic buffer types"
                      {:flags desc-flags :type desc-type}))))
  desc-flags)

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

(defn- validate-descriptor-map
  "Validates a binding map from data literal style."
  [descriptor-idx {:keys [type stages count flags] :or {count 1} :as m}]
  (validate-descriptor-index descriptor-idx)
  (when-not (contains? m :type)
    (throw (ex-info "Descriptor map missing :type" {:descriptor descriptor-idx :descriptor-map m})))
  (when-not (contains? m :stages)
    (throw (ex-info "Descriptor map missing :stages" {:descriptor descriptor-idx :descriptor-map m})))
  (validate-descriptor-type type)
  (validate-stages stages)
  (validate-descriptor-count count)
  (validate-descriptor-flags flags type))

;; ============================================================================
;; Builder Functions
;; ============================================================================

(defn layout
  "Creates an empty descriptor set layout builder map.

  With no arguments, returns a new empty layout:
    (layout) ;; => {:descriptors {}}

  With an existing map, validates and returns it:
    (layout {:descriptors {...}}) ;; validates and returns"
  ([]
   {:descriptors {}})
  ([m]
   (when-not (map? m)
     (throw (ex-info "Layout must be a map" {:got (type m)})))
   (when-not (map? (:descriptors m))
     (throw (ex-info "Layout :descriptors must be a map"
                     {:descriptors (:descriptors m)})))
   (doseq [[descriptor-idx b] (:descriptors m)]
     (validate-descriptor-map descriptor-idx b))
   (validate-layout-flags (:flags m))

   ;; Validate variable-descriptor-count is only on the last binding
   (let [descriptors-with-variable-count
         (filter (fn [[_ desc]] (contains? (:flags desc) :variable-descriptor-count))
                 (:descriptors m))]
     (when (seq descriptors-with-variable-count)
       (let [max-idx (apply max (keys (:descriptors m)))
             variable-indices (map first descriptors-with-variable-count)]
         (when-not (= variable-indices [max-idx])
           (throw (ex-info "Variable descriptor count flag can only be used on the last binding (highest index)"
                           {:variable-descriptor-count-indices (vec variable-indices)
                            :max-index max-idx}))))))

   ;; Validate update-after-bind requires update-after-bind-pool layout flag
   (let [has-update-after-bind?
         (some (fn [[_ desc]] (contains? (:flags desc) :update-after-bind))
               (:descriptors m))]
     (when (and has-update-after-bind?
                (not (contains? (:flags m) :update-after-bind-pool)))
       (throw (ex-info "Descriptors with :update-after-bind flag require :update-after-bind-pool layout flag"
                       {:layout-flags (:flags m)}))))

   m))

(defn descriptor
  "Creates a descriptor or adds one to an existing layout.

  Without a layout (returns [index descriptor-map] entry):
    (descriptor 0 :uniform-buffer #{:vertex :fragment})
    ;; => [0 {:type :uniform-buffer :stages #{:vertex :fragment} :count 1}]

  With a layout (returns updated layout):
    (-> (layout)
        (descriptor 0 :uniform-buffer #{:vertex :fragment})
        (descriptor 1 :sampled-image #{:fragment}))

  Options:
    :count    - Number of descriptors (default 1, use for arrays)
    :flags    - Set of per-descriptor flags (e.g., #{:partially-bound})
    :samplers - Vector of immutable sampler handles
    :name     - Debug name (not sent to Vulkan, for documentation)

  Note: If :update-after-bind flag is used, :update-after-bind-pool will be
  automatically added to the layout flags."
  {:arglists '([index type stages & {:keys [count flags samplers name]}]
               [layout index type stages & {:keys [count flags samplers name]}])}
  [& args]
  (let [[layout? idx desc-type desc-stages & opts]
        (if (map? (first args))
          args
          (cons nil args))
        {desc-count :count
         desc-flags :flags
         desc-samplers :samplers
         desc-name :name
         :or {desc-count 1}} (apply hash-map opts)

        ;; Validate
        _ (validate-descriptor-index idx)
        _ (validate-descriptor-type desc-type)
        _ (validate-stages desc-stages)
        _ (validate-descriptor-count desc-count)
        _ (validate-descriptor-flags desc-flags desc-type)

        ;; Build descriptor map
        descriptor-map (cond-> {:type desc-type
                                :stages desc-stages
                                :count desc-count}
                         desc-flags (assoc :flags desc-flags)
                         desc-samplers (assoc :samplers desc-samplers)
                         desc-name (assoc :name desc-name))]

    (if layout?
      (cond-> (assoc-in layout? [:descriptors idx] descriptor-map)
        ;; Auto-add :update-after-bind-pool if descriptor uses :update-after-bind
        (contains? desc-flags :update-after-bind)
        (update :flags (fnil conj #{}) :update-after-bind-pool))
      [idx descriptor-map])))

(defn flags
  "Sets layout-level create flags on a descriptor set layout.

  Layout flags are different from per-binding flags:
    :update-after-bind-pool - Descriptor sets from this layout can use update-after-bind

  Example:
    (-> (layout)
        (descriptor 0 :uniform-buffer #{:vertex})
        (flags #{:update-after-bind-pool}))"
  [layout flag-set]
  (when-not (set? flag-set)
    (throw (ex-info "Flags must be a set" {:flags flag-set})))
  (validate-layout-flags flag-set)
  (assoc layout :flags flag-set))

;; ============================================================================
;; Layout Creation
;; ============================================================================

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
      {:descriptors {0 {:type :uniform-buffer :stages #{:vertex :fragment}}}})

  The returned layout can be closed with (.close layout) or used with with-open."
  [device layout-map]
  ;; Validate the layout map
  (layout layout-map)

  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [device-handle (handle device)
          descriptors-map (:descriptors layout-map)
          binding-count (count descriptors-map)

          ;; Create binding structs
          binding-buffer (VkDescriptorSetLayoutBinding/calloc binding-count stack)
          _ (doseq [[idx [binding-num b]] (map-indexed vector descriptors-map)]
              (let [binding-struct (.get binding-buffer idx)]
                (-> binding-struct
                    (.binding binding-num)
                    (.descriptorType (get @const/descriptor-types (:type b)))
                    (.descriptorCount (or (:count b) 1))
                    (.stageFlags (const/stages->int (:stages b))))))

          ;; Create layout info
          layout-info (-> (VkDescriptorSetLayoutCreateInfo/calloc stack)
                          (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                          (.pBindings binding-buffer)
                          (.flags (const/layout-flags->int (or (:flags layout-map) #{}))))

          ;; Output pointer
          layout-ptr (.mallocLong stack 1)]

      (check-result (VK10/vkCreateDescriptorSetLayout device-handle layout-info nil layout-ptr))

      (let [layout-handle (.get layout-ptr 0)]
        (with-meta (->DescriptorSetLayout layout-map)
          {:handle layout-handle
           :device device})))))
