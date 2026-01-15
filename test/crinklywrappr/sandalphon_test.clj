(ns crinklywrappr.sandalphon-test
  (:require [clojure.test :refer [deftest is testing]]
            [crinklywrappr.sandalphon.core :as vk]))

(deftest step-1-vulkan-version-test
  (testing "Can query Vulkan instance version"
    (let [version (vk/vulkan-instance-version)]
      (is (instance? crinklywrappr.sandalphon.core.VulkanVersion version))
      (is (pos? (:major version)))
      (is (>= (:minor version) 0))
      (is (>= (:patch version) 0))
      (is (string? (str version)))
      (is (re-matches #"\d+\.\d+\.\d+" (str version)))
      (println (str "Vulkan instance version: " version)))))

(deftest step-2-instance-test
  (testing "Can create Vulkan Instance"
    (with-open [instance (vk/instance! :app-name "Sandalphon Test"
                                       :app-version 1)]
      (is (some? instance))
      (is (instance? crinklywrappr.sandalphon.core.Instance instance))
      (is (instance? java.io.Closeable instance))
      (is (some? (vk/handle instance)) "Handle should be accessible via protocol")
      (is (= "Sandalphon Test" (get-in instance [:config :app-name]))))))

(deftest step-3-layer-properties-test
  (testing "Can enumerate instance layer properties"
    (let [layers (vk/layers)]

      (is (vector? layers))

      (println (str "\nFound " (count layers) " layer(s):"))

      (doseq [layer layers]
        ;; Check Layer record fields
        (is (instance? crinklywrappr.sandalphon.core.Layer layer))
        (is (string? (:layer-name layer)))
        (is (instance? crinklywrappr.sandalphon.core.VulkanVersion (:spec-version layer)))
        (is (string? (:description layer)))

        ;; Check full properties via PropertyQueryable
        (let [props (vk/properties layer)]
          (is (integer? (:implementation-version props)) "Implementation version should be raw integer"))

        (println (str "  - " (:layer-name layer)))
        (println (str "      Spec: " (str (:spec-version layer))
                     ", Impl: " (str (:implementation-version (vk/properties layer)))))
        (println (str "      " (:description layer)))))))

(deftest step-4-physical-devices-test
  (testing "Can enumerate physical devices with properties"
    (with-open [instance (vk/instance! :app-name "Sandalphon Physical Device Test")]
      (let [devices (vk/physical-devices instance)]

        (is (vector? devices))
        (is (seq devices) "Should find at least one physical device")

        (println (str "\nFound " (count devices) " physical device(s):"))

        (doseq [device devices]
          (is (instance? crinklywrappr.sandalphon.core.PhysicalDevice device))
          (is (some? (vk/handle device)) "Handle should be accessible via protocol")

          ;; Check that all fields exist and have valid values
          (is (string? (:device-name device)))
          (is (keyword? (:device-type device)))
          (is (contains? #{:integrated-gpu :discrete-gpu :virtual-gpu :cpu :other}
                        (:device-type device)))
          (is (instance? crinklywrappr.sandalphon.core.VulkanVersion (:api-version device)))

          (println (str "  - " (:device-name device)
                       " (" (name (:device-type device)) ")")))))))

(deftest step-5-physical-device-groups-test
  (testing "Can enumerate physical device groups"
    (with-open [instance (vk/instance! :app-name "Sandalphon Device Groups Test")]
      (let [groups (vk/physical-device-groups instance)]

        (is (vector? groups))

        (println (str "\nFound " (count groups) " device group(s):"))

        (doseq [[i group] (map-indexed vector groups)]
          ;; Check that all fields exist and have valid values
          (is (vector? (:devices group)))
          (is (seq (:devices group)) "Each group should have at least one device")
          (is (boolean? (:subset-allocation group)))

          (println (str "  Group " (inc i) ":"))
          (println (str "    Subset allocation: " (:subset-allocation group)))
          (println "    Devices:")

          ;; Verify each device in the group
          (doseq [device (:devices group)]
            (is (instance? crinklywrappr.sandalphon.core.PhysicalDevice device))
            (is (some? (vk/handle device)) "Handle should be accessible via protocol")
            (is (string? (:device-name device)))
            (is (keyword? (:device-type device)))
            (is (instance? crinklywrappr.sandalphon.core.VulkanVersion (:api-version device)))

            (println (str "      - " (:device-name device)
                         " (" (name (:device-type device)) ")"))))))))

(deftest step-6-logical-device-test
  (testing "Logical device creation and queue management"
    (with-open [instance (vk/instance! :app-name "Sandalphon Logical Device Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]

        (println (str "\nTesting logical device with " (count queue-families) " queue families"))

        (testing "Basic device creation with single queue family"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (instance? crinklywrappr.sandalphon.core.LogicalDevice device))
            (is (instance? java.io.Closeable device))
            (is (some? (vk/handle device)))
            (is (vector? (:queues device)))
            (is (= 1 (count (:queues device))))
            (println "  ✓ Created device with 1 queue")))

        (testing "Device creation with multiple queue families"
          (when (>= (count queue-families) 2)
            (with-open [device (vk/logical-device!
                                :physical-device physical-device
                                :queue-requests [(first queue-families)
                                                (second queue-families)])]
              (is (= 2 (count (:queues device))))
              (println (str "  ✓ Created device with 2 queues from 2 families")))))

        (testing "Device creation with queue-builder and custom queue count"
          (let [family-with-multiple (first (filter #(> (:max-queue-count (vk/properties %)) 1)
                                                    queue-families))]
            (when family-with-multiple
              (let [max-count (:max-queue-count (vk/properties family-with-multiple))
                    requested-count (min 2 max-count)]
                (with-open [device (vk/logical-device!
                                    :physical-device physical-device
                                    :queue-requests [(vk/queue-builder family-with-multiple
                                                                       :queue-count requested-count)])]
                  (is (= requested-count (count (:queues device))))
                  (println (str "  ✓ Created device with " requested-count " queues using queue-builder")))))))

        (testing "Device creation with custom priorities"
          (let [family-with-multiple (first (filter #(> (:max-queue-count (vk/properties %)) 1)
                                                    queue-families))]
            (when family-with-multiple
              (with-open [device (vk/logical-device!
                                  :physical-device physical-device
                                  :queue-requests [(vk/queue-builder family-with-multiple
                                                                     :priorities [1.0 0.5])])]
                (is (= 2 (count (:queues device))))
                (println "  ✓ Created device with custom priorities")))))

        (testing "Mixed queue families and queue builders"
          (when (>= (count queue-families) 2)
            (with-open [device (vk/logical-device!
                                :physical-device physical-device
                                :queue-requests [(first queue-families)
                                                (vk/queue-builder (second queue-families) :queue-count 1)])]
              (is (= 2 (count (:queues device))))
              (println "  ✓ Created device with mixed queue requests"))))

        (testing "Queue record validation"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [queue (first (:queues device))]
              (is (instance? crinklywrappr.sandalphon.core.Queue queue))
              (is (instance? crinklywrappr.sandalphon.core.QueueFamily (:queue-family queue)))
              (is (some? (vk/handle queue)) "Queue should have VkQueue handle")
              (is (number? (vk/index queue)) "Queue should have queue index")
              (println "  ✓ Queue records are valid"))))))))

(deftest step-7-logical-device-validation-test
  (testing "Logical device creation validation"
    (with-open [instance (vk/instance! :app-name "Sandalphon Validation Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]

        (testing "Missing physical-device and physical-device-group"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Must provide either :physical-device or :physical-device-group"
               (vk/logical-device! :queue-requests [(first queue-families)])))
          (println "\n  ✓ Validation rejects missing physical device"))

        (testing "Missing queue-requests"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Must provide :queue-requests"
               (vk/logical-device! :physical-device physical-device)))
          (println "  ✓ Validation rejects missing queue-requests"))

        (testing "Exceeding max-queue-count"
          (let [first-family (first queue-families)
                max-count (:max-queue-count (vk/properties first-family))]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Requested queue-count exceeds max-queue-count"
                 (vk/logical-device!
                  :physical-device physical-device
                  :queue-requests [(vk/queue-builder first-family :queue-count (+ max-count 10))])))
            (println "  ✓ Validation rejects queue-count exceeding maximum")))

        (testing "Physical device not in physical device group"
          (let [groups (vk/physical-device-groups instance)
                devices (vk/physical-devices instance)]
            (when (and (> (count devices) 1) (seq groups))
              (let [group (first groups)
                    other-device (first (remove #(some #{%} (:devices group)) devices))]
                (when other-device
                  (is (thrown-with-msg?
                       clojure.lang.ExceptionInfo
                       #":physical-device must be a member of :physical-device-group"
                       (vk/logical-device!
                        :physical-device other-device
                        :physical-device-group group
                        :queue-requests [(first queue-families)])))
                  (println "  ✓ Validation rejects physical-device not in group"))))))))))
