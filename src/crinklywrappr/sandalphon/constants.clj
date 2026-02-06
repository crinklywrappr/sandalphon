(ns crinklywrappr.sandalphon.constants
  "Shared Vulkan constants discovered via reflection.

  These constants are used by multiple namespaces for converting between
  Clojure keywords and Vulkan integer constants.

  Public vars:
    descriptor-types  - Map of descriptor type keywords to VK10 integers
    shader-stages     - Map of shader stage keywords to VK10 bit constants
    binding-flags     - Map of binding flag keywords to VK12 bit constants
    layout-flags      - Map of layout flag keywords to VK12 bit constants

  Utility functions:
    stages->int       - Combine stage keywords into bit flags
    layout-flags->int - Combine layout flag keywords into bit flags"
  (:require [clojure.string :as sg]
            [clojure.reflect :as reflect]
            [camel-snake-kebab.core :as csk])
  (:import [org.lwjgl.vulkan VK10 VK12]))

;; ============================================================================
;; Descriptor Types
;; ============================================================================

(def descriptor-types
  "Map of descriptor type keywords to VK10 integer constants."
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

;; ============================================================================
;; Shader Stages
;; ============================================================================

(def shader-stages
  "Map of shader stage keywords to VK10 bit constants."
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

(defn stages->int
  "Converts a set of stage keywords to a combined integer using bit-or."
  [stages]
  (transduce (map #(get @shader-stages %)) (completing bit-or) 0 stages))

;; ============================================================================
;; Binding Flags
;; ============================================================================

(def binding-flags
  "Map of descriptor binding flag keywords to VK12 bit constants."
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

;; ============================================================================
;; Layout Flags (Descriptor Set Layout Create Flags)
;; ============================================================================

(def layout-flags
  "Map of layout create flag keywords to VK12 bit constants."
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

(defn layout-flags->int
  "Converts a set of layout flag keywords to a combined integer using bit-or."
  [flags]
  (if (empty? flags)
    0
    (transduce (map #(get @layout-flags %)) (completing bit-or) 0 flags)))
