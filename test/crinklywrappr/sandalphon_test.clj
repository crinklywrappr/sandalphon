(ns crinklywrappr.sandalphon-test
  (:require [clojure.test :refer [deftest is testing]]
            [crinklywrappr.sandalphon.core :as vk]
            [crinklywrappr.sandalphon.protocols :refer [handle properties index]]))

;; Define a Vertex record for testing typed readers/writers
(defrecord Vertex [x y z r g b])

;; Helper to calculate Vertex size in bytes (6 floats)
(def ^:private vertex-size (* 6 Float/BYTES))

;; Create typed reader for Vertex
(def read-vertex!
  (vk/typed-reader
    (constantly vertex-size)
    (fn [bb]
      (->Vertex (.getFloat bb)
                (.getFloat bb)
                (.getFloat bb)
                (.getFloat bb)
                (.getFloat bb)
                (.getFloat bb)))))

;; Implement MemoryBufferWriter for Vertex
(extend-type Vertex
  vk/MemoryBufferWriter
  (write!
    ([this buffer]
     (vk/write! this buffer 0))
    ([this buffer offset]
     (let [bb (doto (java.nio.ByteBuffer/allocate vertex-size)
                (.putFloat (:x this))
                (.putFloat (:y this))
                (.putFloat (:z this))
                (.putFloat (:r this))
                (.putFloat (:g this))
                (.putFloat (:b this))
                (.flip))]
       (vk/write! bb buffer offset)))))

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
      (is (some? (handle instance)) "Handle should be accessible via protocol")
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
        (let [props (properties layer)]
          (is (integer? (:implementation-version props)) "Implementation version should be raw integer"))

        (println (str "  - " (:layer-name layer)))
        (println (str "      Spec: " (str (:spec-version layer))
                     ", Impl: " (str (:implementation-version (properties layer)))))
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
          (is (some? (handle device)) "Handle should be accessible via protocol")

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
            (is (some? (handle device)) "Handle should be accessible via protocol")
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
            (is (some? (handle device)))
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
          (let [family-with-multiple (first (filter #(> (:max-queue-count (properties %)) 1)
                                                    queue-families))]
            (when family-with-multiple
              (let [max-count (:max-queue-count (properties family-with-multiple))
                    requested-count (min 2 max-count)]
                (with-open [device (vk/logical-device!
                                    :physical-device physical-device
                                    :queue-requests [(vk/queue-builder family-with-multiple
                                                                       :queue-count requested-count)])]
                  (is (= requested-count (count (:queues device))))
                  (println (str "  ✓ Created device with " requested-count " queues using queue-builder")))))))

        (testing "Device creation with custom priorities"
          (let [family-with-multiple (first (filter #(> (:max-queue-count (properties %)) 1)
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
              (is (some? (handle queue)) "Queue should have VkQueue handle")
              (is (number? (index queue)) "Queue should have queue index")
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
                max-count (:max-queue-count (properties first-family))]
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

(deftest step-8-memory-allocator-test
  (testing "VulkanMemoryAllocator automatic creation with LogicalDevice"
    (with-open [instance (vk/instance! :app-name "Sandalphon Allocator Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]

        (testing "Allocator created automatically with device"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (let [allocator (:allocator device)]
              (is (some? allocator) "Device should have allocator")
              (is (instance? crinklywrappr.sandalphon.core.VulkanMemoryAllocator allocator))
              (is (instance? java.io.Closeable allocator))
              (is (some? (handle allocator)) "Allocator should have VMA handle")
              (is (= #{} (:flags allocator)) "Default allocator should have empty flags set")
              (println "\n  ✓ LogicalDevice automatically creates VulkanMemoryAllocator"))))

        (testing "Allocator with custom flags"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)]
                              :allocator-flags #{:ext-memory-budget :externally-synchronized})]
            (let [allocator (:allocator device)
                  expected-flags #{:ext-memory-budget :externally-synchronized}]
              (is (some? allocator) "Device should have allocator")
              (is (= expected-flags (:flags allocator)) "Allocator should have custom flags")
              (println "  ✓ Allocator created with custom flags"))))))))

(deftest step-9-buffer-creation-test
  (testing "Buffer creation and management"
    (with-open [instance (vk/instance! :app-name "Sandalphon Buffer Test")]
      (let [physical-device (first (vk/physical-devices instance))
            queue-families (vk/queue-families physical-device)]

        (testing "Basic buffer creation with minimal parameters"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [buffer (vk/memory-buffer! device
                                                  :size 1024
                                                  :usage #{:vertex-buffer})]
              (is (instance? crinklywrappr.sandalphon.core.MemoryBuffer buffer))
              (is (instance? java.io.Closeable buffer))
              (is (some? (handle buffer)) "Buffer should have VkBuffer handle")
              (is (= 1024 (:size buffer)))
              (is (= #{:vertex-buffer} (:usage buffer)))
              (is (= :auto (:memory-usage buffer)) "Default memory usage should be :auto")
              (is (= #{} (:allocation-flags buffer)) "Default allocation flags should be empty")
              (println "\n  ✓ Created basic vertex buffer"))))

        (testing "Buffer with multiple usage flags"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [buffer (vk/memory-buffer! device
                                                  :size 2048
                                                  :usage #{:vertex-buffer :transfer-dst :transfer-src})]
              (is (= #{:vertex-buffer :transfer-dst :transfer-src} (:usage buffer)))
              (println "  ✓ Created buffer with multiple usage flags"))))

        (testing "Buffer with custom memory usage"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [buffer (vk/memory-buffer! device
                                                  :size 512
                                                  :usage #{:uniform-buffer}
                                                  :memory-usage :auto-prefer-host)]
              (is (= :auto-prefer-host (:memory-usage buffer)))
              (println "  ✓ Created buffer with custom memory usage"))))

        (testing "Buffer with allocation flags"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (with-open [buffer (vk/memory-buffer! device
                                                  :size 256
                                                  :usage #{:storage-buffer}
                                                  :allocation-flags #{:mapped :dedicated-memory})]
              (is (= #{:mapped :dedicated-memory} (:allocation-flags buffer)))
              (println "  ✓ Created buffer with allocation flags"))))

        (testing "Concurrent buffer with multiple queue families"
          (when (>= (count queue-families) 2)
            (with-open [device (vk/logical-device!
                                :physical-device physical-device
                                :queue-requests [(first queue-families) (second queue-families)])]
              (with-open [buffer (vk/memory-buffer! device
                                                    :size 1024
                                                    :usage #{:transfer-src :transfer-dst}
                                                    :queue-families [(first queue-families) (second queue-families)])]
                (is (some? buffer))
                (println "  ✓ Created concurrent buffer with multiple queue families")))))

        (testing "Buffer validation - zero size"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Buffer size must be positive"
                 (vk/memory-buffer! device :size 0 :usage #{:vertex-buffer})))
            (println "  ✓ Validation rejects zero size")))

        (testing "Buffer validation - negative size"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Buffer size must be positive"
                 (vk/memory-buffer! device :size -100 :usage #{:vertex-buffer})))
            (println "  ✓ Validation rejects negative size")))

        (testing "Buffer validation - empty usage"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Buffer usage must not be empty"
                 (vk/memory-buffer! device :size 1024 :usage #{})))
            (println "  ✓ Validation rejects empty usage")))

        (testing "Buffer validation - invalid usage flag"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Invalid usage keywords"
                 (vk/memory-buffer! device :size 1024 :usage #{:invalid-flag})))
            (println "  ✓ Validation rejects invalid usage flag")))

        (testing "Buffer validation - invalid allocation flag"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Invalid allocation-flags keywords"
                 (vk/memory-buffer! device :size 1024 :usage #{:vertex-buffer} :allocation-flags #{:bad-flag})))
            (println "  ✓ Validation rejects invalid allocation flag")))

        (testing "Buffer validation - deprecated memory usage"
          (with-open [device (vk/logical-device!
                              :physical-device physical-device
                              :queue-requests [(first queue-families)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Invalid memory usage keyword"
                 (vk/memory-buffer! device :size 1024 :usage #{:vertex-buffer} :memory-usage :invalid)))
            (println "  ✓ Validation rejects deprecated memory usage")))

        (testing "Buffer validation - queue family without queues"
          (when (> (count queue-families) 1)
            (with-open [device (vk/logical-device!
                                :physical-device physical-device
                                :queue-requests [(first queue-families)])]
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"Queue families specified for buffer do not have queues on the device"
                   (vk/memory-buffer! device :size 1024 :usage #{:vertex-buffer}
                                      :queue-families [(second queue-families)])))
              (println "  ✓ Validation rejects queue family without queues")))))))
  (testing "ByteBuffer round-trip test"
    (with-open [instance (vk/instance!)]
      (let [physical-device (first (vk/physical-devices instance))]
        (with-open [device (vk/logical-device! :physical-device physical-device
                                               :queue-requests {(first (vk/queue-families physical-device)) 1})]
          (with-open [buffer (vk/memory-buffer! device
                                                :size 1024
                                                :usage #{:transfer-src}
                                                :allocation-flags #{:host-access-sequential-write :mapped})]
            ;; Generate random data
            (let [random-data (byte-array 256)
                  _ (doto (java.util.Random.)
                      (.nextBytes random-data))

                  ;; Write to buffer
                  write-bb (doto (java.nio.ByteBuffer/wrap random-data)
                             (.position 0))
                  bytes-written (vk/write! write-bb buffer)]

              (is (= 256 bytes-written) "Should write 256 bytes")

              ;; Read back from buffer
              (let [read-bb (java.nio.ByteBuffer/allocate 256)
                    bytes-read (vk/read! read-bb buffer 0 256)]

                (is (= 256 bytes-read) "Should read 256 bytes")

                ;; Verify data matches (read! auto-flips, so ByteBuffer is ready to read)
                (let [read-data (byte-array 256)]
                  (.get read-bb read-data)
                  (is (java.util.Arrays/equals random-data read-data)
                      "Round-trip data should match original")))

              (println "  ✓ ByteBuffer round-trip successful")))))))

  (testing "Custom record round-trip test"
    (with-open [instance (vk/instance!)]
      (let [physical-device (first (vk/physical-devices instance))]
        (with-open [device (vk/logical-device! :physical-device physical-device
                                               :queue-requests {(first (vk/queue-families physical-device)) 1})]
          (with-open [buffer (vk/memory-buffer! device
                                                :size 1024
                                                :usage #{:transfer-src}
                                                :allocation-flags #{:host-access-sequential-write :mapped})]
            ;; Create a vertex using Floats (explicitly cast from doubles)
            ;; GPU shaders use 32-bit floats, not 64-bit doubles
            (let [original-vertex (->Vertex (float 1.5) (float 2.5) (float 3.5)
                                            (float 0.8) (float 0.6) (float 0.4))
                  bytes-written (vk/write! original-vertex buffer)]

              (is (= vertex-size bytes-written) "Should write vertex-size bytes for a vertex")

              ;; Read back the vertex
              (let [read-vertex (read-vertex! buffer)]

                (is (= original-vertex read-vertex) "the structure should match")
                (is (== (:x original-vertex) (:x read-vertex)) "X coordinate should match")
                (is (== (:y original-vertex) (:y read-vertex)) "Y coordinate should match")
                (is (== (:z original-vertex) (:z read-vertex)) "Z coordinate should match")
                (is (== (:r original-vertex) (:r read-vertex)) "R color should match")
                (is (== (:g original-vertex) (:g read-vertex)) "G color should match")
                (is (== (:b original-vertex) (:b read-vertex)) "B color should match"))

              (println "  ✓ Custom record round-trip successful"))))))))

(deftest step-10-command-pool-test
  (testing "Can create and destroy command pool"
    (with-open [instance (vk/instance!)]
      (let [physical-devices (vk/physical-devices instance)]
        (when (seq physical-devices)
          (let [physical-device (first physical-devices)
                queue-families (vk/queue-families physical-device)]
            (when (seq queue-families)
              (with-open [device (vk/logical-device!
                                  :physical-device physical-device
                                  :queue-requests [(first queue-families)])]
                (testing "Create command pool with default flags"
                  (with-open [pool (vk/command-pool! device (first queue-families))]
                    (is (instance? crinklywrappr.sandalphon.core.CommandPool pool))
                    (is (some? (handle pool)) "Pool should have a handle")
                    (is (= (first queue-families) (:queue-family pool)))
                    (is (= #{:reset-command-buffer} (:flags pool)))
                    (println "  ✓ Command pool created with default flags")))

                (testing "Create command pool with custom flags"
                  (with-open [pool (vk/command-pool! device (first queue-families)
                                                     :flags #{:transient})]
                    (is (instance? crinklywrappr.sandalphon.core.CommandPool pool))
                    (is (some? (handle pool)))
                    (is (= #{:transient} (:flags pool)))
                    (println "  ✓ Command pool created with :transient flag")))

                (testing "Validate available command pool flags"
                  (let [flags (vk/command-pool-create-flags)]
                    (is (set? flags))
                    (is (contains? flags :reset-command-buffer))
                    (is (contains? flags :transient))
                    (println (str "  ✓ Available command pool flags: " flags))))

                (testing "Validation rejects queue family not on device"
                  (when (> (count queue-families) 1)
                    ;; Create device with only first queue family
                    (with-open [device-one-queue (vk/logical-device!
                                                  :physical-device physical-device
                                                  :queue-requests [(first queue-families)])]
                      ;; Try to create pool with second queue family (not on device)
                      (is (thrown-with-msg?
                           clojure.lang.ExceptionInfo
                           #"Queue family does not have queues on the device"
                           (vk/command-pool! device-one-queue (second queue-families))))
                      (println "  ✓ Validation rejects queue family not on device"))))))))))))

(deftest step-11-command-buffer-test
  (testing "Can allocate and free command buffers"
    (with-open [instance (vk/instance!)]
      (let [physical-devices (vk/physical-devices instance)]
        (when (seq physical-devices)
          (let [physical-device (first physical-devices)
                queue-families (vk/queue-families physical-device)]
            (when (seq queue-families)
              (with-open [device (vk/logical-device!
                                  :physical-device physical-device
                                  :queue-requests [(first queue-families)])]
                (with-open [pool (vk/command-pool! device (first queue-families))]

                  (testing "Allocate single primary command buffer"
                    (with-open [cmd-buf (vk/command-buffer! pool)]
                      (is (instance? crinklywrappr.sandalphon.core.CommandBuffer cmd-buf))
                      (is (some? (handle cmd-buf)) "Command buffer should have a handle")
                      (is (= :primary (:level cmd-buf)))
                      (is (= pool (:command-pool cmd-buf)))
                      (println "  ✓ Allocated single primary command buffer")))

                  (testing "Allocate multiple primary command buffers"
                    (let [cmd-bufs (vk/command-buffer! pool 3)]
                      (is (vector? cmd-bufs))
                      (is (= 3 (count cmd-bufs)))
                      (doseq [buf cmd-bufs]
                        (is (instance? crinklywrappr.sandalphon.core.CommandBuffer buf))
                        (is (some? (handle buf)))
                        (is (= :primary (:level buf))))
                      ;; Free them
                      (doseq [buf cmd-bufs]
                        (.close buf))
                      (println "  ✓ Allocated and freed 3 primary command buffers")))

                  (testing "Allocate secondary command buffers"
                    (let [cmd-bufs (vk/command-buffer! pool 2 :level :secondary)]
                      (is (vector? cmd-bufs))
                      (is (= 2 (count cmd-bufs)))
                      (doseq [buf cmd-bufs]
                        (is (instance? crinklywrappr.sandalphon.core.CommandBuffer buf))
                        (is (some? (handle buf)))
                        (is (= :secondary (:level buf))))
                      ;; Free them
                      (doseq [buf cmd-bufs]
                        (.close buf))
                      (println "  ✓ Allocated and freed 2 secondary command buffers")))

                  (testing "Validation rejects invalid level"
                    (is (thrown-with-msg?
                         clojure.lang.ExceptionInfo
                         #"Invalid command buffer level"
                         (vk/command-buffer! pool 1 :level :invalid)))
                    (println "  ✓ Validation rejects invalid level")))))))))))
