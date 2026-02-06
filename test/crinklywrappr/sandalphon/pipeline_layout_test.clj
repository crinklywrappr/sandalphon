(ns crinklywrappr.sandalphon.pipeline-layout-test
  (:require [clojure.test :refer [deftest testing is]]
            [crinklywrappr.sandalphon.pipeline-layout :as pl]
            [crinklywrappr.sandalphon.descriptor-set-layout :as dsl]
            [crinklywrappr.sandalphon.core :as vk]
            [crinklywrappr.sandalphon.protocols :refer [handle]]))

;; ============================================================================
;; Query Functions
;; ============================================================================

(deftest shader-stages-test
  (testing "returns a set of keywords"
    (is (set? (pl/shader-stages)))
    (is (every? keyword? (pl/shader-stages))))

  (testing "contains expected stages"
    (is (contains? (pl/shader-stages) :vertex))
    (is (contains? (pl/shader-stages) :fragment))
    (is (contains? (pl/shader-stages) :compute))))

;; ============================================================================
;; Layout Builder
;; ============================================================================

(deftest layout-0-arity-test
  (testing "returns empty layout map"
    (is (= {:set-layouts {} :push-constants []} (pl/layout)))))

;; ============================================================================
;; Push Constants
;; ============================================================================

(deftest push-constants-without-layout-test
  (testing "creates push constant map"
    (let [pc (pl/push-constants 0 64 #{:vertex})]
      (is (= 0 (:offset pc)))
      (is (= 64 (:size pc)))
      (is (= #{:vertex} (:stages pc)))))

  (testing "with multiple stages"
    (let [pc (pl/push-constants 0 128 #{:vertex :fragment})]
      (is (= #{:vertex :fragment} (:stages pc))))))

(deftest push-constants-with-layout-test
  (testing "adds push constant to layout"
    (let [l (pl/push-constants (pl/layout) 0 64 #{:vertex})]
      (is (= 1 (count (:push-constants l))))
      (is (= 0 (get-in l [:push-constants 0 :offset])))))

  (testing "chaining multiple push constants"
    (let [l (-> (pl/layout)
                (pl/push-constants 0 64 #{:vertex})
                (pl/push-constants 64 64 #{:fragment}))]
      (is (= 2 (count (:push-constants l))))
      (is (= [0 64] (mapv :offset (:push-constants l)))))))

(deftest push-constants-validation-test
  (testing "throws on negative offset"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative integer"
          (pl/push-constants -1 64 #{:vertex}))))

  (testing "throws on zero size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
          (pl/push-constants 0 0 #{:vertex}))))

  (testing "throws on negative size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
          (pl/push-constants 0 -64 #{:vertex}))))

  (testing "throws on empty stages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty set"
          (pl/push-constants 0 64 #{}))))

  (testing "throws on invalid stage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
          (pl/push-constants 0 64 #{:invalid-stage})))))

;; ============================================================================
;; All Shader Stages
;; ============================================================================

(deftest all-shader-stages-test
  (testing "all shader stages can be used in push constants"
    (doseq [stage (pl/shader-stages)]
      (let [pc (pl/push-constants 0 64 #{stage})]
        (is (contains? (:stages pc) stage)
            (str "Shader stage " stage " should work")))))

  (testing "all stages can be combined"
    (let [all-stages (pl/shader-stages)
          pc (pl/push-constants 0 64 all-stages)]
      (is (= all-stages (:stages pc))))))

;; ============================================================================
;; Hybrid Style
;; ============================================================================

(deftest hybrid-style-test
  (testing "push constants can be defined separately and combined"
    (let [vertex-pc (pl/push-constants 0 64 #{:vertex})
          fragment-pc (pl/push-constants 64 64 #{:fragment})
          layout-map {:set-layouts {} :push-constants [vertex-pc fragment-pc]}]
      (is (= 2 (count (:push-constants layout-map))))
      (is (= [0 64] (mapv :offset (:push-constants layout-map)))))))

;; ============================================================================
;; build! Function Tests (requires Vulkan device)
;; ============================================================================

(deftest build!-empty-layout-test
  (testing "build! works with empty layout"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (pl/build! device {:set-layouts {} :push-constants []})]
              (is (some? layout))
              (is (instance? crinklywrappr.sandalphon.pipeline_layout.PipelineLayout layout))
              (is (some? (handle layout))))))))))

(deftest build!-with-push-constants-test
  (testing "build! works with push constants only"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout PC Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (pl/build! device
                                 {:set-layouts {}
                                  :push-constants [{:offset 0 :size 64 :stages #{:vertex}}]})]
              (is (some? (handle layout))))))))))

(deftest build!-with-single-set-test
  (testing "build! works with single descriptor set layout"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Set Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [dsl-layout (dsl/build! device
                                     {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}]})]
              (with-open [layout (pl/build! device
                                   {:set-layouts {0 dsl-layout}
                                    :push-constants []})]
                (is (some? (handle layout)))))))))))

(deftest build!-with-multiple-sets-test
  (testing "build! works with multiple descriptor set layouts"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Multi Set Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [scene-dsl (dsl/build! device
                                    {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment}}]})
                        material-dsl (dsl/build! device
                                       {:bindings [{:binding 0 :type :sampled-image :stages #{:fragment}}]})]
              (with-open [layout (pl/build! device
                                   {:set-layouts {0 scene-dsl, 1 material-dsl}
                                    :push-constants []})]
                (is (some? (handle layout)))
                (is (= 2 (count (get-in layout [:layout-map :set-layouts]))))))))))))

(deftest build!-builder-style-test
  (testing "build! works with builder-style layout"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Builder Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [scene-dsl (dsl/build! device
                                    {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}]})]
              (let [layout-map (-> (pl/layout)
                                   (pl/descriptor-set-layout 0 scene-dsl)
                                   (pl/push-constants 0 64 #{:vertex}))]
                (with-open [layout (pl/build! device layout-map)]
                  (is (some? (handle layout))))))))))))

(deftest build!-combined-test
  (testing "build! works with sets and push constants combined"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Combined Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [scene-dsl (dsl/build! device
                                    {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment}}
                                                {:binding 1 :type :uniform-buffer :stages #{:fragment}}]})
                        material-dsl (dsl/build! device
                                       {:bindings [{:binding 0 :type :combined-image-sampler :stages #{:fragment}}
                                                   {:binding 1 :type :combined-image-sampler :stages #{:fragment}}]})]
              (with-open [layout (pl/build! device
                                   {:set-layouts {0 scene-dsl, 1 material-dsl}
                                    :push-constants [{:offset 0 :size 64 :stages #{:vertex}}
                                                     {:offset 64 :size 64 :stages #{:fragment}}]})]
                (is (some? (handle layout)))
                (is (= 2 (count (get-in layout [:layout-map :set-layouts]))))
                (is (= 2 (count (get-in layout [:layout-map :push-constants]))))))))))))

(deftest build!-preserves-layout-map-test
  (testing "build! preserves the original layout-map in the record"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Preserve Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [dsl-layout (dsl/build! device {:bindings []})]
              (let [original-map {:set-layouts {0 dsl-layout}
                                  :push-constants [{:offset 0 :size 64 :stages #{:vertex}}]}]
                (with-open [layout (pl/build! device original-map)]
                  (is (= original-map (:layout-map layout))))))))))))

(deftest build!-metadata-test
  (testing "build! stores device in metadata"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Metadata Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout (pl/build! device {:set-layouts {} :push-constants []})]
              (is (some? (:device (meta layout))))
              (is (= device (:device (meta layout)))))))))))

(deftest build!-multiple-layouts-test
  (testing "Can create multiple pipeline layouts from same device"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Multiple Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [layout1 (pl/build! device {:set-layouts {} :push-constants []})
                        layout2 (pl/build! device {:set-layouts {} :push-constants []})
                        layout3 (pl/build! device {:set-layouts {} :push-constants []})]
              (is (some? (handle layout1)))
              (is (some? (handle layout2)))
              (is (some? (handle layout3)))
              ;; Each layout should have a unique handle
              (is (not= (handle layout1) (handle layout2)))
              (is (not= (handle layout2) (handle layout3))))))))))

(deftest build!-close-test
  (testing "Closing layout destroys the Vulkan object"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Close Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [layout (pl/build! device {:set-layouts {} :push-constants []})]
              (is (some? (handle layout)))
              (.close layout)
              ;; After close, handle still in metadata but using it would be unsafe
              (is (some? (:handle (meta layout)))))))))))

(deftest build!-validation-test
  (testing "build! validates layout before creation"
    (with-open [instance (vk/instance! :app-name "Pipeline Layout Validation Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]
        (when (seq queue-families)
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]

            (testing "Rejects non-map layout"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Layout must be a map"
                    (pl/build! device "not a map"))))

            (testing "Rejects invalid push constant"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                    (pl/build! device
                      {:set-layouts {}
                       :push-constants [{:offset 0 :size 0 :stages #{:vertex}}]}))))

            (testing "Rejects invalid stage in push constant"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid shader stages"
                    (pl/build! device
                      {:set-layouts {}
                       :push-constants [{:offset 0 :size 64 :stages #{:invalid}}]}))))))))))
