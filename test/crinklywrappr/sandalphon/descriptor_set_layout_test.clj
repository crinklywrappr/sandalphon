(ns crinklywrappr.sandalphon.descriptor-set-layout-test
  (:require [clojure.test :refer [deftest testing is are]]
            [crinklywrappr.sandalphon.descriptor-set-layout :as dsl]
            [crinklywrappr.sandalphon.core :as vk]
            [crinklywrappr.sandalphon.protocols :refer [handle]]))

;; ============================================================================
;; Query Functions
;; ============================================================================

(deftest descriptor-types-test
  (testing "returns a set of keywords"
    (is (set? (dsl/descriptor-types)))
    (is (every? keyword? (dsl/descriptor-types))))

  (testing "contains expected types"
    (is (contains? (dsl/descriptor-types) :uniform-buffer))
    (is (contains? (dsl/descriptor-types) :storage-buffer))
    (is (contains? (dsl/descriptor-types) :sampled-image))
    (is (contains? (dsl/descriptor-types) :storage-image))
    (is (contains? (dsl/descriptor-types) :combined-image-sampler))
    (is (contains? (dsl/descriptor-types) :sampler))
    (is (contains? (dsl/descriptor-types) :uniform-buffer-dynamic))
    (is (contains? (dsl/descriptor-types) :storage-buffer-dynamic))
    (is (contains? (dsl/descriptor-types) :input-attachment))))

(deftest shader-stages-test
  (testing "returns a set of keywords"
    (is (set? (dsl/shader-stages)))
    (is (every? keyword? (dsl/shader-stages))))

  (testing "contains expected stages"
    (is (contains? (dsl/shader-stages) :vertex))
    (is (contains? (dsl/shader-stages) :fragment))
    (is (contains? (dsl/shader-stages) :compute))
    (is (contains? (dsl/shader-stages) :geometry))
    (is (contains? (dsl/shader-stages) :tessellation-control))
    (is (contains? (dsl/shader-stages) :tessellation-evaluation))))

(deftest descriptor-flags-test
  (testing "returns a set of keywords"
    (is (set? (dsl/descriptor-flags)))
    (is (every? keyword? (dsl/descriptor-flags))))

  (testing "contains expected flags"
    (is (contains? (dsl/descriptor-flags) :update-after-bind))
    (is (contains? (dsl/descriptor-flags) :partially-bound))
    (is (contains? (dsl/descriptor-flags) :update-unused-while-pending))
    (is (contains? (dsl/descriptor-flags) :variable-descriptor-count))))

(deftest layout-flags-test
  (testing "returns a set of keywords"
    (is (set? (dsl/layout-flags)))
    (is (every? keyword? (dsl/layout-flags))))

  (testing "contains expected flags"
    (is (contains? (dsl/layout-flags) :update-after-bind-pool)))

  (testing "does not contain push-descriptor (roadmap item)"
    (is (not (contains? (dsl/layout-flags) :push-descriptor)))))

;; ============================================================================
;; Layout Builder
;; ============================================================================

(deftest layout-0-arity-test
  (testing "returns empty layout map"
    (is (= {:descriptors {}} (dsl/layout)))))

(deftest layout-1-arity-test
  (testing "validates and returns valid layout"
    (let [m {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}}}}]
      (is (= m (dsl/layout m)))))

  (testing "validates with flags"
    (let [m {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}}}
             :flags #{:update-after-bind-pool}}]
      (is (= m (dsl/layout m)))))

  (testing "throws on non-map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Layout must be a map"
                          (dsl/layout "not a map"))))

  (testing "throws on missing bindings"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                          (dsl/layout {}))))

  (testing "throws on non-map bindings"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                          (dsl/layout {:descriptors []}))))

  (testing "throws on invalid binding - missing :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :type"
                          (dsl/layout {:descriptors {0 {:stages #{:vertex}}}}))))

  (testing "throws on invalid binding - missing :stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :stages"
                          (dsl/layout {:descriptors {0 {:type :uniform-buffer}}}))))

  (testing "throws on invalid descriptor type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor type"
                          (dsl/layout {:descriptors {0 {:type :invalid :stages #{:vertex}}}}))))

  (testing "throws on invalid stage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
                          (dsl/layout {:descriptors {0 {:type :uniform-buffer :stages #{:invalid}}}}))))

  (testing "throws on invalid layout flags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid layout flags"
                          (dsl/layout {:descriptors {} :flags #{:invalid-flag}}))))

  (testing "throws on variable-descriptor-count not on last binding"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"last binding"
                          (dsl/layout {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}
                                                        :flags #{:variable-descriptor-count}}
                                                     1 {:type :uniform-buffer :stages #{:vertex}}}}))))

  (testing "allows variable-descriptor-count on last binding"
    (let [m {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}}
                           1 {:type :sampled-image :stages #{:fragment}
                              :flags #{:variable-descriptor-count}}}}]
      (is (= m (dsl/layout m)))))

  (testing "allows variable-descriptor-count on only binding"
    (let [m {:descriptors {0 {:type :sampled-image :stages #{:fragment}
                              :flags #{:variable-descriptor-count}}}}]
      (is (= m (dsl/layout m)))))

  (testing "throws on update-after-bind without update-after-bind-pool layout flag"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"update-after-bind-pool layout flag"
                          (dsl/layout {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}
                                                        :flags #{:update-after-bind}}}}))))

  (testing "allows update-after-bind with update-after-bind-pool layout flag"
    (let [m {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}
                              :flags #{:update-after-bind}}}
             :flags #{:update-after-bind-pool}}]
      (is (= m (dsl/layout m))))))

;; ============================================================================
;; Binding Function
;; ============================================================================

(deftest binding-without-layout-test
  (testing "returns [binding-num binding-map] tuple"
    (let [[n b] (dsl/descriptor 0 :uniform-buffer #{:vertex})]
      (is (= 0 n))
      (is (= :uniform-buffer (:type b)))
      (is (= #{:vertex} (:stages b)))
      (is (= 1 (:count b)))))

  (testing "with :count option"
    (let [[_ b] (dsl/descriptor 0 :sampled-image #{:fragment} :count 4)]
      (is (= 4 (:count b)))))

  (testing "with :flags option"
    (let [[_ b] (dsl/descriptor 0 :sampled-image #{:fragment} :flags #{:partially-bound})]
      (is (= #{:partially-bound} (:flags b)))))

  (testing "with :name option"
    (let [[_ b] (dsl/descriptor 0 :uniform-buffer #{:vertex} :name "camera")]
      (is (= "camera" (:name b)))))

  (testing "with multiple options"
    (let [[n b] (dsl/descriptor 0 :sampled-image #{:fragment}
                                :count 4
                                :flags #{:partially-bound}
                                :name "textures")]
      (is (= 0 n))
      (is (= 4 (:count b)))
      (is (= #{:partially-bound} (:flags b)))
      (is (= "textures" (:name b)))))

  (testing "with multiple stages"
    (let [[_ b] (dsl/descriptor 0 :uniform-buffer #{:vertex :fragment})]
      (is (= #{:vertex :fragment} (:stages b))))))

(deftest binding-with-layout-test
  (testing "adds descriptor to layout"
    (let [l (dsl/descriptor (dsl/layout) 0 :uniform-buffer #{:vertex})]
      (is (map? l))
      (is (contains? (:descriptors l) 0))
      (is (= :uniform-buffer (get-in l [:descriptors 0 :type])))))

  (testing "chaining multiple descriptors"
    (let [l (-> (dsl/layout)
                (dsl/descriptor 0 :uniform-buffer #{:vertex})
                (dsl/descriptor 1 :sampled-image #{:fragment})
                (dsl/descriptor 2 :storage-buffer #{:compute}))]
      (is (= 3 (count (:descriptors l))))
      (is (= #{0 1 2} (set (keys (:descriptors l)))))))

  (testing "auto-adds update-after-bind-pool layout flag when descriptor uses update-after-bind"
    (let [l (dsl/descriptor (dsl/layout) 0 :uniform-buffer #{:vertex} :flags #{:update-after-bind})]
      (is (contains? (:flags l) :update-after-bind-pool))))

  (testing "preserves existing layout flags when auto-adding update-after-bind-pool"
    (let [l (-> (dsl/layout)
                (dsl/flags #{:update-after-bind-pool})
                (dsl/descriptor 0 :uniform-buffer #{:vertex} :flags #{:update-after-bind}))]
      (is (= #{:update-after-bind-pool} (:flags l)))))

  (testing "auto-added layout passes validation"
    (let [l (-> (dsl/layout)
                (dsl/descriptor 0 :uniform-buffer #{:vertex} :flags #{:update-after-bind}))]
      (is (= l (dsl/layout l))))))

(deftest binding-validation-test
  (testing "throws on negative binding number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative integer"
                          (dsl/descriptor -1 :uniform-buffer #{:vertex}))))

  (testing "throws on non-integer binding number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative integer"
                          (dsl/descriptor "0" :uniform-buffer #{:vertex}))))

  (testing "throws on invalid descriptor type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor type"
                          (dsl/descriptor 0 :invalid-type #{:vertex}))))

  (testing "throws on empty stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty set"
                          (dsl/descriptor 0 :uniform-buffer #{}))))

  (testing "throws on non-set stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty set"
                          (dsl/descriptor 0 :uniform-buffer [:vertex]))))

  (testing "throws on invalid stage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
                          (dsl/descriptor 0 :uniform-buffer #{:invalid-stage}))))

  (testing "throws on zero count"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (dsl/descriptor 0 :uniform-buffer #{:vertex} :count 0))))

  (testing "throws on negative count"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (dsl/descriptor 0 :uniform-buffer #{:vertex} :count -1))))

  (testing "throws on invalid descriptor flags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor flags"
                          (dsl/descriptor 0 :uniform-buffer #{:vertex} :flags #{:invalid-flag}))))

  (testing "throws on variable-descriptor-count with dynamic buffer types"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Variable descriptor count cannot be used with dynamic buffer types"
                          (dsl/descriptor 0 :uniform-buffer-dynamic #{:vertex} :flags #{:variable-descriptor-count})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Variable descriptor count cannot be used with dynamic buffer types"
                          (dsl/descriptor 0 :storage-buffer-dynamic #{:vertex} :flags #{:variable-descriptor-count}))))

  (testing "allows variable-descriptor-count with non-dynamic types"
    (let [[idx desc] (dsl/descriptor 0 :uniform-buffer #{:vertex} :flags #{:variable-descriptor-count})]
      (is (= 0 idx))
      (is (= #{:variable-descriptor-count} (:flags desc))))
    (let [[idx desc] (dsl/descriptor 0 :storage-buffer #{:vertex} :flags #{:variable-descriptor-count})]
      (is (= 0 idx))
      (is (= #{:variable-descriptor-count} (:flags desc))))))

;; ============================================================================
;; Flags Function
;; ============================================================================

(deftest flags-test
  (testing "sets flags on layout"
    (let [l (dsl/flags (dsl/layout) #{:update-after-bind-pool})]
      (is (= #{:update-after-bind-pool} (:flags l)))))

  (testing "works in pipeline"
    (let [l (-> (dsl/layout)
                (dsl/descriptor 0 :uniform-buffer #{:vertex})
                (dsl/flags #{:update-after-bind-pool}))]
      (is (= #{:update-after-bind-pool} (:flags l)))
      (is (= 1 (count (:descriptors l))))))

  (testing "throws on non-set flags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a set"
                          (dsl/flags (dsl/layout) :update-after-bind-pool))))

  (testing "throws on invalid flag"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid layout flags"
                          (dsl/flags (dsl/layout) #{:invalid-flag})))))

;; ============================================================================
;; Hybrid Style
;; ============================================================================

(deftest hybrid-style-test
  (testing "bindings can be defined separately and combined"
    (let [camera (dsl/descriptor 0 :uniform-buffer #{:vertex :fragment})
          lights (dsl/descriptor 1 :uniform-buffer #{:vertex :fragment})
          textures (dsl/descriptor 2 :sampled-image #{:fragment} :count 4)
          layout-map {:descriptors (into {} [camera lights textures])}]
      (is (= 3 (count (:descriptors (dsl/layout layout-map)))))
      (is (= #{0 1 2} (set (keys (:descriptors layout-map))))))))

;; ============================================================================
;; All Descriptor Types
;; ============================================================================

(deftest all-descriptor-types-test
  (testing "all descriptor types can be used in bindings"
    (doseq [dtype (dsl/descriptor-types)]
      (let [[n b] (dsl/descriptor 0 dtype #{:compute})]
        (is (= 0 n) (str "Binding number should be 0 for " dtype))
        (is (= dtype (:type b)) (str "Type should be " dtype)))))

  (testing "all descriptor types can be used in layout validation"
    (doseq [[idx dtype] (map-indexed vector (dsl/descriptor-types))]
      (let [layout-map {:descriptors {idx {:type dtype :stages #{:compute}}}}]
        (is (= layout-map (dsl/layout layout-map))
            (str "Descriptor type " dtype " should validate"))))))

;; ============================================================================
;; All Shader Stages
;; ============================================================================

(deftest all-shader-stages-test
  (testing "all shader stages can be used in bindings"
    (doseq [stage (dsl/shader-stages)]
      (let [[_ b] (dsl/descriptor 0 :uniform-buffer #{stage})]
        (is (contains? (:stages b) stage)
            (str "Shader stage " stage " should work")))))

  (testing "all stages can be combined"
    (let [all-stages (dsl/shader-stages)
          [_ b] (dsl/descriptor 0 :uniform-buffer all-stages)]
      (is (= all-stages (:stages b))))))

;; ============================================================================
;; All Binding Flags
;; ============================================================================

(deftest all-descriptor-flags-test
  (testing "all binding flags can be used"
    (doseq [flag (dsl/descriptor-flags)]
      (let [[_ b] (dsl/descriptor 0 :uniform-buffer #{:vertex} :flags #{flag})]
        (is (contains? (:flags b) flag)
            (str "Binding flag " flag " should work")))))

  (testing "all flags can be combined"
    (let [all-flags (dsl/descriptor-flags)
          [_ b] (dsl/descriptor 0 :uniform-buffer #{:vertex} :flags all-flags)]
      (is (= all-flags (:flags b))))))

;; ============================================================================
;; All Layout Flags
;; ============================================================================

(deftest all-layout-flags-test
  (testing "all layout flags can be used"
    (doseq [flag (dsl/layout-flags)]
      (let [l (dsl/flags (dsl/layout) #{flag})]
        (is (contains? (:flags l) flag)
            (str "Layout flag " flag " should work")))))

  (testing "all layout flags can be combined"
    (let [all-flags (dsl/layout-flags)
          l (dsl/flags (dsl/layout) all-flags)]
      (is (= all-flags (:flags l))))))

;; ============================================================================
;; Complex Layouts
;; ============================================================================

(deftest complex-layout-test
  (testing "complex multi-binding layout validates"
    (let [layout-map {:descriptors {0 {:type :uniform-buffer :stages #{:vertex :fragment} :count 1}
                                    1 {:type :uniform-buffer :stages #{:vertex :fragment} :count 1}
                                    2 {:type :sampled-image :stages #{:fragment} :count 4
                                       :flags #{:partially-bound}}
                                    3 {:type :storage-buffer :stages #{:compute} :count 1}
                                    4 {:type :storage-image :stages #{:compute} :count 1}}
                      :flags #{:update-after-bind-pool}}]
      (is (= layout-map (dsl/layout layout-map)))))

  (testing "complex layout via builder"
    (let [l (-> (dsl/layout)
                (dsl/descriptor 0 :uniform-buffer #{:vertex :fragment} :name "camera")
                (dsl/descriptor 1 :uniform-buffer #{:vertex :fragment} :name "lights")
                (dsl/descriptor 2 :sampled-image #{:fragment} :count 4 :flags #{:partially-bound} :name "textures")
                (dsl/descriptor 3 :storage-buffer #{:compute} :name "input")
                (dsl/descriptor 4 :storage-image #{:compute} :name "output")
                (dsl/flags #{:update-after-bind-pool}))]
      (is (= 5 (count (:descriptors l))))
      (is (= #{:update-after-bind-pool} (:flags l)))
      (is (= #{"camera" "lights" "textures" "input" "output"}
             (set (map :name (vals (:descriptors l)))))))))

;; ============================================================================
;; build! Function Tests (requires Vulkan device)
;; ============================================================================

(deftest build!-basic-test
  (testing "build! creates a DescriptorSetLayout with valid handle"
    (with-open [instance (vk/instance! :app-name "DSL Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (dsl/build! device
                                           {:descriptors {0 {:type :uniform-buffer
                                                             :stages #{:vertex :fragment}}}})]
              (is (some? layout))
              (is (instance? crinklywrappr.sandalphon.descriptor_set_layout.DescriptorSetLayout layout))
              (is (instance? java.io.Closeable layout))
              (is (some? (handle layout)) "Layout should have a valid handle")
              (is (pos? (handle layout)) "Handle should be a positive long"))))))))

(deftest build!-empty-bindings-test
  (testing "build! works with empty bindings"
    (with-open [instance (vk/instance! :app-name "DSL Empty Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (dsl/build! device {:descriptors {}})]
              (is (some? (handle layout))))))))))

(deftest build!-multiple-bindings-test
  (testing "build! works with multiple bindings"
    (with-open [instance (vk/instance! :app-name "DSL Multiple Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (dsl/build! device
                                           {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}}
                                                          1 {:type :uniform-buffer :stages #{:fragment}}
                                                          2 {:type :sampled-image :stages #{:fragment}}
                                                          3 {:type :storage-buffer :stages #{:compute}}}})]
              (is (some? (handle layout)))
              (is (= 4 (count (get-in layout [:layout-map :descriptors])))))))))))

(deftest build!-with-array-count-test
  (testing "build! works with descriptor arrays"
    (with-open [instance (vk/instance! :app-name "DSL Array Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (dsl/build! device
                                           {:descriptors {0 {:type :sampled-image
                                                             :stages #{:fragment}
                                                             :count 16}}})]
              (is (some? (handle layout)))
              (is (= 16 (get-in layout [:layout-map :descriptors 0 :count]))))))))))

(deftest build!-all-descriptor-types-test
  (testing "build! works with all descriptor types"
    (with-open [instance (vk/instance! :app-name "DSL All Types Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (doseq [[idx dtype] (map-indexed vector (dsl/descriptor-types))]
              (with-open [layout (dsl/build! device
                                             {:descriptors {idx {:type dtype
                                                                 :stages #{:compute}}}})]
                (is (some? (handle layout))
                    (str "Descriptor type " dtype " should create valid layout"))))))))))

(deftest build!-all-shader-stages-test
  (testing "build! works with all shader stages"
    (with-open [instance (vk/instance! :app-name "DSL All Stages Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            ;; Test each stage individually
            (doseq [stage (dsl/shader-stages)]
              (with-open [layout (dsl/build! device
                                             {:descriptors {0 {:type :uniform-buffer
                                                               :stages #{stage}}}})]
                (is (some? (handle layout))
                    (str "Shader stage " stage " should create valid layout"))))

            ;; Test all stages combined
            (with-open [layout (dsl/build! device
                                           {:descriptors {0 {:type :uniform-buffer
                                                             :stages (dsl/shader-stages)}}})]
              (is (some? (handle layout))
                  "All shader stages combined should create valid layout"))))))))

(deftest build!-builder-style-test
  (testing "build! works with builder-style layout"
    (with-open [instance (vk/instance! :app-name "DSL Builder Style Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [layout-map (-> (dsl/layout)
                                 (dsl/descriptor 0 :uniform-buffer #{:vertex :fragment})
                                 (dsl/descriptor 1 :sampled-image #{:fragment} :count 4))]
              (with-open [layout (dsl/build! device layout-map)]
                (is (some? (handle layout)))
                (is (= 2 (count (get-in layout [:layout-map :descriptors]))))))))))))

(deftest build!-hybrid-style-test
  (testing "build! works with hybrid-style layout"
    (with-open [instance (vk/instance! :app-name "DSL Hybrid Style Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [camera (dsl/descriptor 0 :uniform-buffer #{:vertex :fragment})
                  textures (dsl/descriptor 1 :sampled-image #{:fragment} :count 4)
                  layout-map {:descriptors (into {} [camera textures])}]
              (with-open [layout (dsl/build! device layout-map)]
                (is (some? (handle layout)))
                (is (= 2 (count (get-in layout [:layout-map :descriptors]))))))))))))

(deftest build!-preserves-layout-map-test
  (testing "build! preserves the original layout-map in the record"
    (with-open [instance (vk/instance! :app-name "DSL Preserve Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [original-map {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}}
                                              1 {:type :sampled-image :stages #{:fragment} :count 4}}
                                :flags #{:update-after-bind-pool}}]
              (with-open [layout (dsl/build! device original-map)]
                (is (= original-map (:layout-map layout)))))))))))

(deftest build!-metadata-test
  (testing "build! stores device in metadata"
    (with-open [instance (vk/instance! :app-name "DSL Metadata Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (dsl/build! device {:descriptors {}})]
              (is (some? (:device (meta layout))))
              (is (= device (:device (meta layout)))))))))))

(deftest build!-multiple-layouts-test
  (testing "Can create multiple descriptor set layouts from same device"
    (with-open [instance (vk/instance! :app-name "DSL Multiple Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout1 (dsl/build! device {:descriptors {}})
                        layout2 (dsl/build! device {:descriptors {}})
                        layout3 (dsl/build! device {:descriptors {}})]
              (is (some? (handle layout1)))
              (is (some? (handle layout2)))
              (is (some? (handle layout3)))
              ;; Each layout should have a unique handle
              (is (not= (handle layout1) (handle layout2)))
              (is (not= (handle layout2) (handle layout3))))))))))

(deftest build!-close-test
  (testing "Closing layout destroys the Vulkan object"
    (with-open [instance (vk/instance! :app-name "DSL Close Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [layout (dsl/build! device {:descriptors {}})]
              (is (some? (handle layout)))
              (.close layout)
              ;; After close, handle still in metadata but using it would be unsafe
              (is (some? (:handle (meta layout)))))))))))

(deftest build!-validation-test
  (testing "build! validates layout before creation"
    (with-open [instance (vk/instance! :app-name "DSL Validation Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]

            (testing "Rejects non-map layout"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Layout must be a map"
                                    (dsl/build! device "not a map"))))

            (testing "Rejects invalid descriptor type"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor type"
                                    (dsl/build! device
                                                {:descriptors {0 {:type :invalid :stages #{:vertex}}}}))))

            (testing "Rejects invalid stage"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
                                    (dsl/build! device
                                                {:descriptors {0 {:type :uniform-buffer :stages #{:invalid}}}}))))))))))

(deftest build!-sparse-bindings-test
  (testing "build! works with non-contiguous binding numbers"
    (with-open [instance (vk/instance! :app-name "DSL Sparse Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (dsl/build! device
                                           {:descriptors {0 {:type :uniform-buffer :stages #{:vertex}}
                                                          5 {:type :uniform-buffer :stages #{:fragment}}
                                                          10 {:type :sampled-image :stages #{:fragment}}}})]
              (is (some? (handle layout)))
              (is (= 3 (count (get-in layout [:layout-map :descriptors])))))))))))
