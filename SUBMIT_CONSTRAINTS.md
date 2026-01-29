# Submit! Design Constraints

This document captures known design constraints in the `submit!` function that may be addressed in future iterations.

## 1. Cyclic Inputs Cause GPU Deadlock

The topological sort uses a visited map that prevents infinite loops, but if a user manually constructs a cyclic execution graph (A depends on B, B depends on A), the resulting semaphore dependencies will deadlock the GPU.

The API naturally creates DAGs via `execute`, `wait-for`, and `then-execute`, so cycles require deliberate misuse. However, runtime cycle detection could provide a better error message.

**Potential fix:** Detect cycles during topological sort and throw an exception with a clear message.

## 2. All Queues Must Be From the Same Logical Device

Semaphores are device-scoped Vulkan objects. If different execution nodes use queues from different logical devices, semaphore operations will fail or cause undefined behavior.

Currently this is documented but not validated at runtime.

**Potential fix:** Validate during tree traversal that all queues share the same logical device, throw early with a clear error.

## 3. Queue Locking Uses Object Identity

The `locking queue` mechanism for serializing `vkQueueSubmit` calls relies on the Queue record being the same object instance across all usages. If someone creates a value-equal but identity-distinct Queue record, submissions could race.

In practice, queues come from `(:queues (meta device))` and are stable, but this is a subtle invariant.

**Potential fix:** Add a `ReentrantLock` to Queue metadata at creation time, or use a global ConcurrentHashMap keyed by queue handle.

## 4. Fan-Out at Root Level Not Directly Supported

The `submit!` function takes a single root execution. If you want fan-out (A feeds both B and C, submitted separately), each `submit!` would rediscover and resubmit A.

The intended pattern is to build the entire DAG first, then submit once:

```clojure
(let [a (execute a-builder queue)
      b (-> (execute b-builder queue) (wait-for a))
      c (-> (execute c-builder queue) (wait-for a))
      root (-> (execute noop-builder queue) (wait-for b c))]
  (submit! allocator root))
```

**Potential fix:** Could add a multi-root `submit-all!` that takes multiple executions and deduplicates shared subgraphs.

## 5. IdentityHashMap for DAG Correctness

The topological sort and semaphore assignment use `java.util.IdentityHashMap` because Execution is a defrecord with value-based equality. Two structurally identical but logically distinct executions (e.g., same builder submitted twice) must be treated as separate nodes.

This is correct but unusual for Clojure code. An alternative would be adding a unique ID to each Execution at creation time.

**Potential fix:** Add `(gensym)` or UUID to Execution, making value equality distinguish nodes naturally.
