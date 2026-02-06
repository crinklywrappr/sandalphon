(ns crinklywrappr.sandalphon.pipeline-layout
  "Builder functions for creating Vulkan pipeline layouts.

  A pipeline layout combines descriptor set layouts with push constant ranges.

  Three usage styles are supported:

  Builder style (threading macro):
    (-> (layout)
        (descriptor-set-layout 0 scene-dsl)
        (descriptor-set-layout 1 material-dsl)
        (push-constants 0 64 #{:vertex})
        (build! device))

  Data literal style:
    (build! device
      {:set-layouts {0 scene-dsl, 1 material-dsl}
       :push-constants [{:offset 0 :size 64 :stages #{:vertex}}]})

  Hybrid style (build push constants separately):
    (def my-pc (push-constants 0 64 #{:vertex}))
    (build! device {:set-layouts {0 dsl} :push-constants [my-pc]})"
  (:require [crinklywrappr.sandalphon.constants :as const]
            [crinklywrappr.sandalphon.error :refer [check-result]]
            [crinklywrappr.sandalphon.protocols :refer [IVulkanHandle handle]]
            [taoensso.trove :as log])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan VK10
            VkPipelineLayoutCreateInfo
            VkPushConstantRange]
           [java.io Closeable]))

;; ============================================================================
;; Query Functions
;; ============================================================================

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

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn- validate-set-layout-index [idx]
  (when-not (and (integer? idx) (>= idx 0))
    (throw (ex-info "Set layout index must be a non-negative integer"
                    {:set-layout-index idx})))
  idx)

(defn- validate-push-constant-offset [offset]
  (when-not (and (integer? offset) (>= offset 0))
    (throw (ex-info "Push constant offset must be a non-negative integer"
                    {:offset offset})))
  offset)

(defn- validate-push-constant-size [size]
  (when-not (and (integer? size) (pos? size))
    (throw (ex-info "Push constant size must be a positive integer"
                    {:size size})))
  (when (> size 128)
    (log/log! {:level :warn
               :msg "Push constant size exceeds Vulkan minimum guarantee of 128 bytes"
               :data {:size size}}))
  size)

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

(defn- validate-push-constant-map
  "Validates a push constant map."
  [{:keys [offset size stages] :as m}]
  (when-not (contains? m :offset)
    (throw (ex-info "Push constant map missing :offset" {:push-constant m})))
  (when-not (contains? m :size)
    (throw (ex-info "Push constant map missing :size" {:push-constant m})))
  (when-not (contains? m :stages)
    (throw (ex-info "Push constant map missing :stages" {:push-constant m})))
  (validate-push-constant-offset offset)
  (validate-push-constant-size size)
  (validate-stages stages))

(defn- validate-descriptor-set-layout [dsl]
  (when-not (satisfies? IVulkanHandle dsl)
    (throw (ex-info "Descriptor set layout must implement IVulkanHandle (use dsl/build! to create)"
                    {:got (type dsl)})))
  (when-not (handle dsl)
    (throw (ex-info "Descriptor set layout has no valid handle"
                    {:layout dsl})))
  dsl)

(defn- check-set-layout-indices
  "Warns if set layout indices have gaps (e.g., 0, 2 without 1)."
  [set-layouts-map]
  (when (seq set-layouts-map)
    (let [indices (sort (keys set-layouts-map))
          expected (range (count indices))]
      (when (not= indices (vec expected))
        (log/log! {:level :warn
                   :msg "Set layout indices have gaps - Vulkan expects contiguous indices starting at 0"
                   :data {:indices indices}})))))

;; ============================================================================
;; Builder Functions
;; ============================================================================

(defn layout
  "Creates an empty pipeline layout builder map.

  With no arguments, returns a new empty layout:
    (layout) ;; => {:set-layouts {} :push-constants []}

  With an existing map, validates and returns it:
    (layout {:set-layouts {...} :push-constants [...]}) ;; validates and returns"
  ([]
   {:set-layouts {} :push-constants []})
  ([m]
   (when-not (map? m)
     (throw (ex-info "Layout must be a map" {:got (type m)})))
   (when-not (map? (:set-layouts m))
     (throw (ex-info "Layout :set-layouts must be a map"
                     {:set-layouts (:set-layouts m)})))
   (when-not (vector? (:push-constants m))
     (throw (ex-info "Layout :push-constants must be a vector"
                     {:push-constants (:push-constants m)})))
   ;; Validate each set layout
   (doseq [[idx dsl] (:set-layouts m)]
     (validate-set-layout-index idx)
     (validate-descriptor-set-layout dsl))
   (check-set-layout-indices (:set-layouts m))
   ;; Validate each push constant
   (doseq [pc (:push-constants m)]
     (validate-push-constant-map pc))
   m))

(defn descriptor-set-layout
  "Adds a descriptor set layout at the specified index.

  Without a layout (returns a set layout entry for later use):
    (descriptor-set-layout 0 my-dsl) ;; => [0 my-dsl]

  With a layout (returns updated layout):
    (-> (layout)
        (descriptor-set-layout 0 scene-dsl)
        (descriptor-set-layout 1 material-dsl))"
  ([index dsl]
   (validate-set-layout-index index)
   (validate-descriptor-set-layout dsl)
   [index dsl])
  ([layout index dsl]
   (validate-set-layout-index index)
   (validate-descriptor-set-layout dsl)
   (assoc-in layout [:set-layouts index] dsl)))

(defn push-constants
  "Creates a push constant range or adds one to an existing layout.

  Without a layout (returns push constant map):
    (push-constants 0 64 #{:vertex})
    ;; => {:offset 0 :size 64 :stages #{:vertex}}

  With a layout (returns updated layout):
    (-> (layout)
        (push-constants 0 64 #{:vertex})
        (push-constants 64 64 #{:fragment}))"
  ([offset size stages]
   (validate-push-constant-offset offset)
   (validate-push-constant-size size)
   (validate-stages stages)
   {:offset offset :size size :stages stages})
  ([layout offset size stages]
   (let [pc (push-constants offset size stages)]
     (update layout :push-constants conj pc))))

;; ============================================================================
;; Layout Creation
;; ============================================================================

(defrecord PipelineLayout [layout-map]
  IVulkanHandle
  (handle [this] (:handle (meta this)))

  Closeable
  (close [this]
    (when-let [h (handle this)]
      (let [device (:device (meta this))
            device-handle (handle device)]
        (VK10/vkDestroyPipelineLayout device-handle h nil)))))

(defn build!
  "Builds a VkPipelineLayout from a layout map.

  Takes a device and a layout map (from builder or data literal).
  Returns a PipelineLayout record implementing IVulkanHandle and Closeable.

  Example:
    (build! device
      {:set-layouts {0 scene-dsl, 1 material-dsl}
       :push-constants [{:offset 0 :size 64 :stages #{:vertex}}]})

  The returned layout can be closed with (.close layout) or used with with-open."
  [device layout-map]
  ;; Validate the layout map
  (layout layout-map)

  (with-open [^MemoryStack stack (MemoryStack/stackPush)]
    (let [device-handle (handle device)
          set-layouts-map (:set-layouts layout-map)
          push-constants-vec (:push-constants layout-map)

          ;; Get set layouts in order (0, 1, 2, ...)
          set-layout-count (if (empty? set-layouts-map) 0 (inc (apply max (keys set-layouts-map))))
          set-layouts-buffer (when (pos? set-layout-count)
                               (let [buf (.mallocLong stack set-layout-count)]
                                 (doseq [i (range set-layout-count)]
                                   (.put buf i (if-let [dsl (get set-layouts-map i)]
                                                 (handle dsl)
                                                 VK10/VK_NULL_HANDLE)))
                                 buf))

          ;; Create push constant ranges
          pc-count (count push-constants-vec)
          pc-buffer (when (pos? pc-count)
                      (let [buf (VkPushConstantRange/calloc pc-count stack)]
                        (doseq [[idx pc] (map-indexed vector push-constants-vec)]
                          (-> (.get buf idx)
                              (.stageFlags (const/stages->int (:stages pc)))
                              (.offset (:offset pc))
                              (.size (:size pc))))
                        buf))

          ;; Create layout info
          layout-info (cond-> (-> (VkPipelineLayoutCreateInfo/calloc stack)
                                  (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO))
                        set-layouts-buffer (.pSetLayouts set-layouts-buffer)
                        pc-buffer (.pPushConstantRanges pc-buffer))

          ;; Output pointer
          layout-ptr (.mallocLong stack 1)]

      (check-result (VK10/vkCreatePipelineLayout device-handle layout-info nil layout-ptr))

      (let [layout-handle (.get layout-ptr 0)]
        (with-meta (->PipelineLayout layout-map)
          {:handle layout-handle
           :device device})))))
