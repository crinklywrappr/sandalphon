# Command Buffer Submission and Synchronization

This document describes the design for submitting command buffers to queues and managing GPU-CPU synchronization in Sandalphon.

## Design Philosophy

Users should express **what** to execute and **when** dependencies exist, not **how** synchronization primitives work. Semaphores, fences, and pipeline stages are implementation details that should be hidden behind a composable API.

## Phase 1: Current Design (Simple & Correct)

### Core API

#### `execute`
Creates an execution plan for a command buffer on a queue.

```clojure
(execute cmd-buffer queue)
;; Returns: Execution record
```

#### `submit!`
Submits the execution to the GPU, optionally calling a callback when complete.

```clojure
(submit! execution)
;; Returns: Clojure future wrapping Vulkan fence

(submit! execution callback-fn)
;; Calls callback-fn when GPU completes
;; Returns: Clojure future
```

The future:
- Blocks on `@future` or `(deref future)` until GPU completes
- Supports timeout: `(deref future 1000 :timeout)`
- Checks completion without blocking: `(realized? future)`

#### `wait-for`
Adds dependencies to an execution (waits for previous work to complete).

```clojure
(wait-for execution prev-execution)
(wait-for execution prev-execution1 prev-execution2 ...)
;; Returns: New execution with dependencies
```

#### `then-execute`
Chains executions - new execution waits for previous.

```clojure
(then-execute prev-execution cmd-buffer queue)
;; Equivalent to:
;; (-> (execute cmd-buffer queue)
;;     (wait-for prev-execution))
```

### Usage Examples

**Simple single-queue submission:**
```clojure
(let [result (-> (execute cmd-buffer graphics-queue)
                 (submit!))]
  ;; Do CPU work...
  @result)  ; Wait for GPU to finish
```

**With callback:**
```clojure
(-> (execute cmd-buffer queue)
    (submit! (fn [result]
               (println "GPU finished!"))))
```

**Multi-queue with dependencies:**
```clojure
;; Upload data on transfer queue
(def upload-done
  (-> (execute transfer-cmd transfer-queue)
      (submit!)))

;; Render on graphics queue, wait for upload
(-> (execute graphics-cmd graphics-queue)
    (wait-for upload-done)
    (submit!))
```

**Chained executions:**
```clojure
(-> (execute compute-cmd compute-queue)
    (then-execute graphics-cmd graphics-queue)
    (then-execute present-cmd graphics-queue)
    (submit!))
```

**Multiple dependencies (fan-in):**
```clojure
(let [upload1 (-> (execute cmd1 transfer-queue) (submit!))
      upload2 (-> (execute cmd2 transfer-queue) (submit!))
      render (-> (execute graphics-cmd graphics-queue)
                 (wait-for upload1 upload2)
                 (submit!))]
  @render)
```

### Implementation Details

**Execution Record:**
```clojure
(defrecord Execution [cmd-buffers queue dependencies metadata])
```

**Phase 1 internals:**
- `cmd-buffers`: Vector of command buffers (Phase 1: always single element)
- `queue`: VkQueue to submit to
- `dependencies`: Vector of previous Execution records
- `metadata`: Extensibility for future phases

**Synchronization:**
- **Same queue, no dependencies:** Sequential execution (no semaphore needed)
- **Cross-queue or dependencies:** Create VkSemaphore per dependency
- **Pipeline stage:** Use `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` (conservative)
- **CPU wait:** Create VkFence, wrap in Clojure future
- **Callback:** Future watches fence, calls callback when signaled

**Cleanup:**
- Semaphores destroyed after fence signals
- Fence destroyed when future is realized
- Command buffers returned to pool (manual or via callback)

### Performance Characteristics

**Expected overhead per submission:**
- Semaphore creation: ~1-5μs (if dependencies exist)
- Fence creation: ~1μs
- vkQueueSubmit: ~10-100μs
- Future creation: <1μs

**Typical frame (60 FPS, 16ms budget):**
- 5-20 submissions: ~100-200μs total (< 2% of frame time)
- Acceptable for most applications

## Phase 2: Optimizations (Future)

### Auto-Batching Same-Queue Submissions

**Problem:** Multiple submissions to the same queue are expensive.

**Solution:** Detect when `then-execute` uses the same queue and batch into a single `vkQueueSubmit`.

```clojure
;; Phase 1: 2 vkQueueSubmit calls
(-> (execute cmd1 graphics-queue)
    (then-execute cmd2 graphics-queue)
    (submit!))

;; Phase 2: 1 vkQueueSubmit call (internal optimization, same API)
;; Execution record stores multiple cmd-buffers
```

**Implementation:**
- `then-execute` detects `prev-execution.queue == new-queue`
- Merges `cmd-buffers` vectors instead of creating dependency
- `submit!` submits all buffers in single call

**Expected improvement:** 50-90% reduction in submission overhead for batched commands.

### Resource Pooling

**Semaphore pool:**
- Maintain pool of VkSemaphore objects
- Reuse instead of create/destroy per dependency
- Return to pool after fence signals

**Fence pool:**
- Reuse VkFence objects across submissions
- Reset and return to pool when future is realized

**Expected improvement:** 80-95% reduction in semaphore/fence allocation overhead.

### Optional Pipeline Stage Hints

Add optional `:stage` parameter for advanced users to reduce GPU idle time.

```clojure
;; Phase 1: Conservative (waits for all work)
(wait-for prev-execution)

;; Phase 2: Precise (waits only for specific pipeline stage)
(wait-for prev-execution :stage :vertex-input)
```

**Common stages:**
- `:transfer` - DMA/copy operations
- `:vertex-input` - Reading vertex buffers
- `:fragment-shader` - Fragment shader execution
- `:color-attachment-output` - Writing to framebuffer
- `:compute-shader` - Compute shader execution
- `:all-commands` - Everything (default, most conservative)

**Backwards compatible:** Existing code continues to use `:all-commands`.

## Phase 3: Advanced Features (Speculative)

### Timeline Semaphores (Vulkan 1.2)

More efficient semaphore primitive with better performance and fewer objects needed.

**Change:** Internal only - same API, different primitive.

### Multi-Frame Pipelining

Allow multiple frames in flight for maximum throughput.

```clojure
(submit! execution :frame-id 0)
(submit! execution :frame-id 1)
;; Frame 0 and 1 execute in parallel on GPU
```

**API addition:** Optional `:frame-id` parameter to `submit!`.

### Async Command Buffer Recording

Record command buffers on worker threads while previous frame executes.

**Requires:** Thread-safe command pool management (already designed with Apache Commons Pool).

## Design Constraints & Trade-offs

### Why Not Return Future from `execute`?

**Considered:**
```clojure
(execute cmd-buffer queue)  ; Returns future immediately?
```

**Rejected because:**
1. `execute` is a planning step, not an action - shouldn't start GPU work
2. Loses composability - can't chain multiple executions before submit
3. Makes batching (Phase 2) impossible

**Chosen design:**
```clojure
(-> (execute cmd1 queue)
    (then-execute cmd2 queue)  ; Composable
    (submit!))  ; Action happens here, returns future
```

### Why Wrap in Clojure Future Instead of Custom Type?

**Pros of Clojure future:**
- Familiar API (`@`, `deref`, `realized?`)
- Works with existing Clojure tooling
- No new concepts for users to learn

**Pros of custom type:**
- Could add Vulkan-specific methods (e.g., `get-fence-handle`)
- Could support cancellation (though Vulkan doesn't support this well)

**Decision:** Use Clojure future for simplicity. Add custom type if compelling use cases emerge.

### Why Not Implicit Synchronization?

Some APIs (like Vulkano's GpuFuture) track resource usage and insert barriers automatically.

**Rejected because:**
1. Requires tracking all resource accesses (complex, error-prone)
2. Rust's ownership model helps here; Clojure's doesn't
3. Explicit is better - users understand what's happening
4. Validation layers catch missing synchronization

**Chosen design:** Explicit dependencies via `wait-for` / `then-execute`.

## Queue Families and Multi-Queue

Vulkan distinguishes between **queue families** (categories) and **queues** (individual workers).

**Example:**
- Graphics family: queue 0, queue 1
- Compute family: queue 0
- Transfer family: queue 0, queue 1, queue 2

**Phase 1:** Users pass any queue to `execute`. Synchronization works across families and within families.

**Future consideration:** Helpers to allocate queues per thread for parallel submission.

## Relationship to Command Buffer Builder

The submission API is orthogonal to the builder API:

**Build phase (planning):**
```clojure
(def cmd-buffer
  (-> (acquire-command-builder allocator graphics-family)
      (graphics/draw! ...)
      (build!)))
```

**Execution phase (action):**
```clojure
(-> (execute cmd-buffer graphics-queue)
    (submit!))
```

Separation allows:
- Recording on one thread, submitting on another
- Reusing command buffers across multiple submissions (with `:simultaneous-use`)
- Pre-recording command buffers at startup

## Error Handling

**Phase 1:**
- Vulkan errors throw `ex-info` with error details
- Callbacks receive exceptions, not thrown (logged/handled gracefully)

**Future:**
- Validation for common mistakes (e.g., submitting to wrong queue family)
- Better error messages for synchronization issues

## Testing Strategy

**Unit tests:**
- Single queue submission
- Multi-queue with dependencies
- Multiple dependencies (fan-in)
- Chained executions (fan-out)
- Callbacks

**Integration tests:**
- Actual GPU submission with transfer → graphics pipeline
- Timing verification (fence signals after work completes)

**Performance tests:**
- Measure overhead of submission API
- Compare with raw Vulkan (should be <5% overhead)

## References

- [Vulkano submission docs](https://vulkano.rs/03-buffer-creation/02-example-operation.html#submission-and-synchronization)
- [Vulkano GpuFuture trait](https://docs.rs/vulkano/0.34.0/vulkano/sync/future/trait.GpuFuture.html)
- [Vulkan spec: Queue Submission](https://registry.khronos.org/vulkan/specs/1.3-extensions/html/chap6.html#devsandqueues-submission)
- [Vulkan spec: Synchronization](https://registry.khronos.org/vulkan/specs/1.3-extensions/html/chap7.html)

## Open Questions

1. Should `submit!` with callback block until submission (not completion), or return immediately?
   - **Leaning toward:** Return immediately, callback fires when GPU completes

2. Should we support "submit without fence" for fire-and-forget operations?
   - **Leaning toward:** No, always create fence for safety (can optimize later)

3. Should futures be cancellable? (Vulkan doesn't support cancellation well)
   - **Leaning toward:** No, simplify API

4. Should we support vkQueueSubmit2 (Vulkan 1.3)?
   - **Phase 3:** Maybe, if compelling benefits emerge
