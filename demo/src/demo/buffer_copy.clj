(ns demo.buffer-copy
  "Demonstrates GPU buffer-to-buffer copy using the command buffer allocator.

  This example:
  1. Creates a Vulkan instance and logical device
  2. Allocates source and destination memory buffers
  3. Writes test data to the source buffer
  4. Records and submits a copy command via the DAG-based submission system
  5. Verifies the destination buffer contains the copied data"
  (:require [crinklywrappr.sandalphon.core :as vk]
            [crinklywrappr.sandalphon.commands.transfer :as t])
  (:import [java.nio ByteBuffer]
           [java.util Arrays]))

(defn run []
  (with-open [instance (vk/instance!)]
    (let [physical-device (first (vk/physical-devices instance))]
      (with-open [logical-device (vk/logical-device!
                                   :physical-device physical-device
                                   :queue-requests (vk/queue-families physical-device))]
        (let [queues (vk/queues logical-device)
              queue (first queues)]
          (println "Using device:" (:device-name physical-device))
          (println "Queue family flags:" (-> queue :queue-family :flags))

          (with-open [src-buffer (vk/memory-buffer!
                                   logical-device
                                   :size 64
                                   :usage #{:transfer-src}
                                   :allocation-flags #{:host-access-sequential-write :mapped})
                      dst-buffer (vk/memory-buffer!
                                   logical-device
                                   :size 64
                                   :usage #{:transfer-dst}
                                   :allocation-flags #{:host-access-random :mapped})]

            ;; Initialize source buffer with test data [0, 1, 2, ..., 63]
            (let [test-data (byte-array 64 (range 64))]
              (vk/write! (ByteBuffer/wrap test-data) src-buffer)
              (println "Wrote" (count test-data) "bytes to source buffer")

              ;; Execute GPU copy and verify in on-success callback
              (with-open [allocator (vk/command-buffer-allocator! logical-device)]
                (println "Submitting copy command...")
                @(-> (t/copy-buffer! src-buffer dst-buffer {})
                     (vk/build! :usage #{:one-time-submit})
                     (vk/execute queue)
                     (vk/submit! allocator
                                 :on-success
                                 (fn []
                                   (let [dst-data (byte-array 64)
                                         dst-buf (ByteBuffer/wrap dst-data)]
                                     (vk/read! dst-buf dst-buffer)
                                     (println "Expected:" (vec (take 8 test-data)) "...")
                                     (println "Got:     " (vec (take 8 dst-data)) "...")
                                     (if (Arrays/equals test-data dst-data)
                                       (println "SUCCESS: Buffer copy verified!")
                                       (println "FAILURE: Buffers do not match"))))
                                 :on-error
                                 (fn [err]
                                   (println "ERROR:" err))))))))))))

(comment
  ;; Run the demo
  (run)
  )
