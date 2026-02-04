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

(deftest binding-flags-test
  (testing "returns a set of keywords"
    (is (set? (dsl/binding-flags)))
    (is (every? keyword? (dsl/binding-flags))))

  (testing "contains expected flags"
    (is (contains? (dsl/binding-flags) :update-after-bind))
    (is (contains? (dsl/binding-flags) :partially-bound))
    (is (contains? (dsl/binding-flags) :update-unused-while-pending))
    (is (contains? (dsl/binding-flags) :variable-descriptor-count))))

(deftest layout-flags-test
  (testing "returns a set of keywords"
    (is (set? (dsl/layout-flags)))
    (is (every? keyword? (dsl/layout-flags))))

  (testing "contains expected flags"
    (is (contains? (dsl/layout-flags) :update-after-bind-pool)))

  (testing "does not contain push-descriptor (not yet implemented)"
    (is (not (contains? (dsl/layout-flags) :push-descriptor)))))

;; ============================================================================
;; Layout Builder
;; ============================================================================

(deftest layout-0-arity-test
  (testing "returns empty layout map"
    (is (= {:bindings []} (dsl/layout)))))

(deftest layout-1-arity-test
  (testing "validates and returns valid layout"
    (let [valid {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}]}]
      (is (= valid (dsl/layout valid)))))

  (testing "accepts empty bindings"
    (is (= {:bindings []} (dsl/layout {:bindings []}))))

  (testing "accepts layout with flags"
    (let [with-flags {:bindings [] :flags #{:update-after-bind-pool}}]
      (is (= with-flags (dsl/layout with-flags)))))

  (testing "throws on non-map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Layout must be a map"
          (dsl/layout "not a map"))))

  (testing "throws on missing bindings"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
          (dsl/layout {}))))

  (testing "throws on non-vector bindings"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
          (dsl/layout {:bindings '()}))))

  (testing "throws on invalid binding - missing :binding"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :binding"
          (dsl/layout {:bindings [{:type :uniform-buffer :stages #{:vertex}}]}))))

  (testing "throws on invalid binding - missing :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :type"
          (dsl/layout {:bindings [{:binding 0 :stages #{:vertex}}]}))))

  (testing "throws on invalid binding - missing :stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :stages"
          (dsl/layout {:bindings [{:binding 0 :type :uniform-buffer}]}))))

  (testing "throws on invalid descriptor type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor type"
          (dsl/layout {:bindings [{:binding 0 :type :invalid :stages #{:vertex}}]}))))

  (testing "throws on invalid stage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
          (dsl/layout {:bindings [{:binding 0 :type :uniform-buffer :stages #{:invalid}}]}))))

  (testing "throws on invalid layout flags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid layout flags"
          (dsl/layout {:bindings [] :flags #{:invalid-flag}})))))

;; ============================================================================
;; Binding Function
;; ============================================================================

(deftest binding-without-layout-test
  (testing "3-arity creates binding map"
    (let [b (dsl/binding 0 :uniform-buffer #{:vertex})]
      (is (= 0 (:binding b)))
      (is (= :uniform-buffer (:type b)))
      (is (= #{:vertex} (:stages b)))
      (is (= 1 (:count b)))))

  (testing "with :count option"
    (let [b (dsl/binding 0 :sampled-image #{:fragment} :count 4)]
      (is (= 4 (:count b)))))

  (testing "with :flags option"
    (let [b (dsl/binding 0 :sampled-image #{:fragment} :flags #{:partially-bound})]
      (is (= #{:partially-bound} (:flags b)))))

  (testing "with :binding-name option"
    (let [b (dsl/binding 0 :uniform-buffer #{:vertex} :binding-name "camera")]
      (is (= "camera" (:binding-name b)))))

  (testing "with multiple options"
    (let [b (dsl/binding 0 :sampled-image #{:fragment}
                         :count 4
                         :flags #{:partially-bound}
                         :binding-name "textures")]
      (is (= 4 (:count b)))
      (is (= #{:partially-bound} (:flags b)))
      (is (= "textures" (:binding-name b)))))

  (testing "with multiple stages"
    (let [b (dsl/binding 0 :uniform-buffer #{:vertex :fragment})]
      (is (= #{:vertex :fragment} (:stages b))))))

(deftest binding-with-layout-test
  (testing "4-arity adds binding to layout"
    (let [l (dsl/binding (dsl/layout) 0 :uniform-buffer #{:vertex})]
      (is (= 1 (count (:bindings l))))
      (is (= 0 (get-in l [:bindings 0 :binding])))))

  (testing "with options adds binding to layout"
    (let [l (dsl/binding (dsl/layout) 0 :sampled-image #{:fragment} :count 4)]
      (is (= 4 (get-in l [:bindings 0 :count])))))

  (testing "chaining multiple bindings"
    (let [l (-> (dsl/layout)
                (dsl/binding 0 :uniform-buffer #{:vertex :fragment})
                (dsl/binding 1 :uniform-buffer #{:vertex :fragment})
                (dsl/binding 2 :sampled-image #{:fragment} :count 4))]
      (is (= 3 (count (:bindings l))))
      (is (= [0 1 2] (mapv :binding (:bindings l)))))))

(deftest binding-validation-test
  (testing "throws on negative binding number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative integer"
          (dsl/binding -1 :uniform-buffer #{:vertex}))))

  (testing "throws on non-integer binding number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative integer"
          (dsl/binding "0" :uniform-buffer #{:vertex}))))

  (testing "throws on invalid descriptor type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor type"
          (dsl/binding 0 :invalid-type #{:vertex}))))

  (testing "throws on empty stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty set"
          (dsl/binding 0 :uniform-buffer #{}))))

  (testing "throws on non-set stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty set"
          (dsl/binding 0 :uniform-buffer [:vertex]))))

  (testing "throws on invalid stage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
          (dsl/binding 0 :uniform-buffer #{:invalid-stage}))))

  (testing "throws on zero count"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
          (dsl/binding 0 :uniform-buffer #{:vertex} :count 0))))

  (testing "throws on negative count"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
          (dsl/binding 0 :uniform-buffer #{:vertex} :count -1))))

  (testing "throws on invalid binding flags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid binding flags"
          (dsl/binding 0 :uniform-buffer #{:vertex} :flags #{:invalid-flag})))))

;; ============================================================================
;; Flags Function
;; ============================================================================

(deftest flags-test
  (testing "sets flags on layout"
    (let [l (dsl/flags (dsl/layout) #{:update-after-bind-pool})]
      (is (= #{:update-after-bind-pool} (:flags l)))))

  (testing "works in pipeline"
    (let [l (-> (dsl/layout)
                (dsl/binding 0 :uniform-buffer #{:vertex})
                (dsl/flags #{:update-after-bind-pool}))]
      (is (= #{:update-after-bind-pool} (:flags l)))
      (is (= 1 (count (:bindings l))))))

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
    (let [camera (dsl/binding 0 :uniform-buffer #{:vertex :fragment})
          lights (dsl/binding 1 :uniform-buffer #{:vertex :fragment})
          textures (dsl/binding 2 :sampled-image #{:fragment} :count 4)
          layout-map {:bindings [camera lights textures]}]
      (is (= 3 (count (:bindings (dsl/layout layout-map)))))
      (is (= [0 1 2] (mapv :binding (:bindings layout-map)))))))

;; ============================================================================
;; All Descriptor Types
;; ============================================================================

(deftest all-descriptor-types-test
  (testing "all descriptor types can be used in bindings"
    (doseq [dtype (dsl/descriptor-types)]
      (let [b (dsl/binding 0 dtype #{:compute})]
        (is (= dtype (:type b))
            (str "Descriptor type " dtype " should work"))))))

;; ============================================================================
;; All Shader Stages
;; ============================================================================

(deftest all-shader-stages-test
  (testing "all shader stages can be used in bindings"
    (doseq [stage (dsl/shader-stages)]
      (let [b (dsl/binding 0 :uniform-buffer #{stage})]
        (is (contains? (:stages b) stage)
            (str "Shader stage " stage " should work")))))

  (testing "all stages can be combined"
    (let [all-stages (dsl/shader-stages)
          b (dsl/binding 0 :uniform-buffer all-stages)]
      (is (= all-stages (:stages b))))))

;; ============================================================================
;; All Binding Flags
;; ============================================================================

(deftest all-binding-flags-test
  (testing "all binding flags can be used"
    (doseq [flag (dsl/binding-flags)]
      (let [b (dsl/binding 0 :uniform-buffer #{:compute} :flags #{flag})]
        (is (contains? (:flags b) flag)
            (str "Binding flag " flag " should work")))))

  (testing "all binding flags can be combined"
    (let [all-flags (dsl/binding-flags)
          b (dsl/binding 0 :uniform-buffer #{:compute} :flags all-flags)]
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
    (let [layout-map {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment} :count 1}
                                 {:binding 1 :type :uniform-buffer :stages #{:vertex :fragment} :count 1}
                                 {:binding 2 :type :sampled-image :stages #{:fragment} :count 4
                                  :flags #{:partially-bound}}
                                 {:binding 3 :type :storage-buffer :stages #{:compute} :count 1}
                                 {:binding 4 :type :storage-image :stages #{:compute} :count 1}]
                      :flags #{:update-after-bind-pool}}]
      (is (= layout-map (dsl/layout layout-map)))))

  (testing "complex layout via builder"
    (let [l (-> (dsl/layout)
                (dsl/binding 0 :uniform-buffer #{:vertex :fragment} :binding-name "camera")
                (dsl/binding 1 :uniform-buffer #{:vertex :fragment} :binding-name "lights")
                (dsl/binding 2 :sampled-image #{:fragment} :count 4 :flags #{:partially-bound} :binding-name "textures")
                (dsl/binding 3 :storage-buffer #{:compute} :binding-name "input")
                (dsl/binding 4 :storage-image #{:compute} :binding-name "output")
                (dsl/flags #{:update-after-bind-pool}))]
      (is (= 5 (count (:bindings l))))
      (is (= #{:update-after-bind-pool} (:flags l)))
      (is (= ["camera" "lights" "textures" "input" "output"]
             (mapv :binding-name (:bindings l)))))))

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
                                 {:bindings [{:binding 0
                                              :type :uniform-buffer
                                              :stages #{:vertex :fragment}}]})]
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
            (with-open [layout (dsl/build! device {:bindings []})]
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
                                 {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}
                                             {:binding 1 :type :uniform-buffer :stages #{:fragment}}
                                             {:binding 2 :type :sampled-image :stages #{:fragment}}
                                             {:binding 3 :type :storage-buffer :stages #{:compute}}]})]
              (is (some? (handle layout)))
              (is (= 4 (count (get-in layout [:layout-map :bindings])))))))))))

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
                                 {:bindings [{:binding 0
                                              :type :sampled-image
                                              :stages #{:fragment}
                                              :count 16}]})]
              (is (some? (handle layout)))
              (is (= 16 (get-in layout [:layout-map :bindings 0 :count]))))))))))

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
                                   {:bindings [{:binding idx
                                                :type dtype
                                                :stages #{:compute}}]})]
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
                                   {:bindings [{:binding 0
                                                :type :uniform-buffer
                                                :stages #{stage}}]})]
                (is (some? (handle layout))
                    (str "Shader stage " stage " should create valid layout"))))

            ;; Test all stages combined
            (with-open [layout (dsl/build! device
                                 {:bindings [{:binding 0
                                              :type :uniform-buffer
                                              :stages (dsl/shader-stages)}]})]
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
                                 (dsl/binding 0 :uniform-buffer #{:vertex :fragment})
                                 (dsl/binding 1 :uniform-buffer #{:vertex :fragment})
                                 (dsl/binding 2 :sampled-image #{:fragment} :count 4))]
              (with-open [layout (dsl/build! device layout-map)]
                (is (some? (handle layout)))
                (is (= 3 (count (get-in layout [:layout-map :bindings]))))))))))))

(deftest build!-hybrid-style-test
  (testing "build! works with hybrid-style layout"
    (with-open [instance (vk/instance! :app-name "DSL Hybrid Style Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [camera (dsl/binding 0 :uniform-buffer #{:vertex :fragment})
                  lights (dsl/binding 1 :uniform-buffer #{:fragment})
                  textures (dsl/binding 2 :sampled-image #{:fragment} :count 4)]
              (with-open [layout (dsl/build! device {:bindings [camera lights textures]})]
                (is (some? (handle layout)))))))))))

(deftest build!-preserves-layout-map-test
  (testing "build! preserves the original layout-map in the record"
    (with-open [instance (vk/instance! :app-name "DSL Preserve Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [original-map {:bindings [{:binding 0
                                            :type :uniform-buffer
                                            :stages #{:vertex}
                                            :count 1}
                                           {:binding 1
                                            :type :storage-buffer
                                            :stages #{:compute}
                                            :count 1}]}]
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
            (with-open [layout (dsl/build! device {:bindings []})]
              (is (some? (:device (meta layout))))
              (is (= device (:device (meta layout)))))))))))

(deftest build!-multiple-layouts-test
  (testing "Can create multiple layouts from same device"
    (with-open [instance (vk/instance! :app-name "DSL Multiple Layouts Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout1 (dsl/build! device
                                  {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}]})
                        layout2 (dsl/build! device
                                  {:bindings [{:binding 0 :type :storage-buffer :stages #{:compute}}]})
                        layout3 (dsl/build! device
                                  {:bindings [{:binding 0 :type :sampled-image :stages #{:fragment}}]})]
              (is (some? (handle layout1)))
              (is (some? (handle layout2)))
              (is (some? (handle layout3)))
              ;; Each layout should have a unique handle
              (is (not= (handle layout1) (handle layout2)))
              (is (not= (handle layout2) (handle layout3)))
              (is (not= (handle layout1) (handle layout3))))))))))

(deftest build!-close-test
  (testing "Closing layout destroys the Vulkan object"
    (with-open [instance (vk/instance! :app-name "DSL Close Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [layout (dsl/build! device {:bindings []})]
              (is (some? (handle layout)))
              (.close layout)
              ;; After close, the layout should still have the handle in metadata
              ;; but using it would be unsafe (this tests close doesn't throw)
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

            (testing "Rejects invalid descriptor type"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid descriptor type"
                    (dsl/build! device
                      {:bindings [{:binding 0 :type :invalid :stages #{:vertex}}]}))))

            (testing "Rejects invalid stage"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
                    (dsl/build! device
                      {:bindings [{:binding 0 :type :uniform-buffer :stages #{:invalid}}]}))))

            (testing "Rejects missing binding"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :binding"
                    (dsl/build! device
                      {:bindings [{:type :uniform-buffer :stages #{:vertex}}]}))))

            (testing "Rejects non-vector bindings"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
                    (dsl/build! device {:bindings '()}))))))))))

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
                                 {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}
                                             {:binding 5 :type :uniform-buffer :stages #{:fragment}}
                                             {:binding 10 :type :sampled-image :stages #{:fragment}}]})]
              (is (some? (handle layout))))))))))

(deftest build!-complex-layout-test
  (testing "build! works with complex real-world layout"
    (with-open [instance (vk/instance! :app-name "DSL Complex Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            ;; Simulate a typical PBR material layout
            (let [layout-map (-> (dsl/layout)
                                 ;; Camera/scene uniforms
                                 (dsl/binding 0 :uniform-buffer #{:vertex :fragment}
                                              :binding-name "camera")
                                 ;; Model transforms
                                 (dsl/binding 1 :uniform-buffer #{:vertex}
                                              :binding-name "model")
                                 ;; Material parameters
                                 (dsl/binding 2 :uniform-buffer #{:fragment}
                                              :binding-name "material")
                                 ;; Albedo texture
                                 (dsl/binding 3 :combined-image-sampler #{:fragment}
                                              :binding-name "albedo")
                                 ;; Normal map
                                 (dsl/binding 4 :combined-image-sampler #{:fragment}
                                              :binding-name "normal")
                                 ;; Metallic-roughness map
                                 (dsl/binding 5 :combined-image-sampler #{:fragment}
                                              :binding-name "metallicRoughness")
                                 ;; Environment map (cubemap array)
                                 (dsl/binding 6 :combined-image-sampler #{:fragment}
                                              :count 6 :binding-name "envMap"))]
              (with-open [layout (dsl/build! device layout-map)]
                (is (some? (handle layout)))
                (is (= 7 (count (get-in layout [:layout-map :bindings]))))))))))))
