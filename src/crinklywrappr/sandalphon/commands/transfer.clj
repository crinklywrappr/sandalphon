(ns crinklywrappr.sandalphon.commands.transfer
  "Transfer command functions for Vulkan command buffers.

  Transfer commands copy data between buffers, fill buffers with constant values,
  and transfer data between images and buffers. These commands can execute on any
  queue that supports transfer operations."
  (:require [crinklywrappr.sandalphon.protocols :refer [handle]])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan VK10 VkBufferCopy]))

;; ============================================================================
;; Transfer Commands
;; ============================================================================

(defn copy-buffer!
  "Queues a buffer copy command to the command buffer builder.

  This command copies data from one buffer to another. The source and destination
  buffers can be the same buffer (for internal copies) as long as regions don't overlap.

  Parameters:
    builder    - CommandBufferBuilder to add the command to
    src-buffer - Source Buffer to copy from
    dst-buffer - Destination Buffer to copy to

  Optional keyword parameters:
    :regions - Vector of copy region maps, each containing:
               :src-offset - Offset in source buffer (default: 0)
               :dst-offset - Offset in destination buffer (default: 0)
               :size       - Number of bytes to copy (required)

               If not specified, copies entire source buffer to destination
               (requires buffers to be the same size)

  Returns:
    New CommandBufferBuilder with the copy command queued

  Example:
    ;; Copy entire buffer
    (-> builder
        (copy-buffer! src dst))

    ;; Copy specific regions
    (-> builder
        (copy-buffer! src dst
          :regions [{:src-offset 0 :dst-offset 1024 :size 512}
                    {:src-offset 512 :dst-offset 0 :size 256}]))

  Validation:
    - Source and destination buffers must have valid handles
    - Regions must not be empty
    - Each region size must be > 0
    - Offsets + size must not exceed buffer sizes"
  [builder src-buffer dst-buffer & {:keys [regions]}]
  (let [src-handle (handle src-buffer)
        dst-handle (handle dst-buffer)
        src-size (:size src-buffer)
        dst-size (:size dst-buffer)]

    ;; Validation
    (when-not src-handle
      (throw (ex-info "Source buffer has no valid handle" {:buffer src-buffer})))
    (when-not dst-handle
      (throw (ex-info "Destination buffer has no valid handle" {:buffer dst-buffer})))

    (let [regions (or regions [{:src-offset 0 :dst-offset 0 :size src-size}])]
      (when (empty? regions)
        (throw (ex-info "Copy regions cannot be empty" {:regions regions})))

      ;; Validate each region
      (doseq [{:keys [src-offset dst-offset size] :as region} regions]
        (when-not size
          (throw (ex-info "Region size is required" {:region region})))
        (when (<= size 0)
          (throw (ex-info "Region size must be > 0" {:region region :size size})))
        (let [src-offset (or src-offset 0)
              dst-offset (or dst-offset 0)]
          (when (> (+ src-offset size) src-size)
            (throw (ex-info "Source region exceeds buffer size"
                            {:region region
                             :src-offset src-offset
                             :size size
                             :src-buffer-size src-size})))
          (when (> (+ dst-offset size) dst-size)
            (throw (ex-info "Destination region exceeds buffer size"
                            {:region region
                             :dst-offset dst-offset
                             :size size
                             :dst-buffer-size dst-size})))))

      ;; Create command record
      (let [cmd {:name :copy-buffer
                 :args {:src src-buffer
                        :dst dst-buffer
                        :regions regions}
                 :record-fn (fn [cmd-buffer-handle]
                              (with-open [^MemoryStack stack (MemoryStack/stackPush)]
                                (let [region-count (count regions)
                                      vk-regions (VkBufferCopy/calloc region-count stack)]
                                  (doseq [[i {:keys [src-offset dst-offset size]}] (map-indexed vector regions)]
                                    (-> (.get vk-regions i)
                                        (.srcOffset (or src-offset 0))
                                        (.dstOffset (or dst-offset 0))
                                        (.size size)))
                                  (VK10/vkCmdCopyBuffer cmd-buffer-handle src-handle dst-handle vk-regions))))}]
        ;; Return new builder with command added
        (update builder :commands-to-record conj cmd)))))

(defn fill-buffer!
  "Queues a buffer fill command to the command buffer builder.

  This command fills a buffer range with a constant 32-bit value. The fill operation
  is performed by the GPU and is typically faster than CPU-side memset for large buffers.

  Parameters:
    builder - CommandBufferBuilder to add the command to
    buffer  - Buffer to fill
    value   - 32-bit unsigned integer value to fill with

  Optional keyword parameters:
    :offset - Byte offset to start filling (default: 0, must be multiple of 4)
    :size   - Number of bytes to fill (default: entire buffer, must be multiple of 4)

  Returns:
    New CommandBufferBuilder with the fill command queued

  Example:
    ;; Fill entire buffer with zeros
    (-> builder
        (fill-buffer! buffer 0))

    ;; Fill specific range
    (-> builder
        (fill-buffer! buffer 0xDEADBEEF
          :offset 1024
          :size 2048))

  Validation:
    - Buffer must have a valid handle
    - Value must be an integer in range [0, 0xFFFFFFFF]
    - Offset must be a multiple of 4
    - Size must be a multiple of 4 or VK_WHOLE_SIZE
    - Offset + size must not exceed buffer size"
  [builder buffer value & {:keys [offset size]}]
  (let [buffer-handle (handle buffer)
        buffer-size (:size buffer)
        offset (or offset 0)
        size (or size buffer-size)]

    ;; Validation
    (when-not buffer-handle
      (throw (ex-info "Buffer has no valid handle" {:buffer buffer})))
    (when-not (and (integer? value) (<= 0 value 0xFFFFFFFF))
      (throw (ex-info "Value must be a 32-bit unsigned integer"
                      {:value value
                       :hint "Must be in range [0, 0xFFFFFFFF]"})))
    (when-not (zero? (mod offset 4))
      (throw (ex-info "Offset must be a multiple of 4"
                      {:offset offset})))
    (when-not (or (= size VK10/VK_WHOLE_SIZE) (zero? (mod size 4)))
      (throw (ex-info "Size must be a multiple of 4 or VK_WHOLE_SIZE"
                      {:size size})))
    (when (> (+ offset size) buffer-size)
      (throw (ex-info "Fill range exceeds buffer size"
                      {:offset offset
                       :size size
                       :buffer-size buffer-size})))

    ;; Create command record
    (let [cmd {:name :fill-buffer
               :args {:buffer buffer
                      :value value
                      :offset offset
                      :size size}
               :record-fn (fn [cmd-buffer-handle]
                            (VK10/vkCmdFillBuffer cmd-buffer-handle buffer-handle offset size value))}]
      ;; Return new builder with command added
      (update builder :commands-to-record conj cmd))))
