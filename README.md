# com.github.crinklywrappr/sandalphon

FIXME: my new library.

## Usage

FIXME: write usage documentation!

Invoke a library API function from the command-line:

    $ clojure -X crinklywrappr.sandalphon/foo :a 1 :b '"two"'
    {:a 1, :b "two"} "Hello, World!"

Run the project's tests (they'll fail until you edit them):

    $ clojure -T:build test

Run the project's CI pipeline and build a JAR (this will fail until you edit the tests to pass):

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables (requires the `ci` task be run first):

    $ clojure -T:build deploy

Your library will be deployed to com.github.crinklywrappr/sandalphon on clojars.org by default.

## Common Pitfalls

### Use-After-Free with Lazy Sequences

Clojure's lazy sequences can cause crashes when combined with Vulkan's manual resource management.

**The Problem:**

Vulkan resources (Instance, PhysicalDevice, etc.) wrap native handles that become invalid when the resource is closed. If you return a lazy sequence that references these handles, and then close the resource, the lazy sequence will crash when realized.

**Example (CRASHES):**
```clojure
;; BAD: map returns lazy sequence
(with-open [instance (vk/instance!)]
  (map vk/properties (vk/physical-devices instance)))
;; Instance closes here, but map hasn't executed yet

;; Later, when REPL prints the result...
;; CRASH! Tries to call vk/properties on dead instance handle
```

**Why This Crashes:**

1. `map` returns a lazy sequence without executing
2. `with-open` closes the instance
3. REPL (or your code) tries to realize the lazy sequence
4. `vk/properties` tries to query Vulkan with a destroyed instance handle
5. Segmentation fault - no validation layer can catch this (instance is already destroyed)

**Solutions:**

**Option 1: Force evaluation to complete within the instance lifetime:**
```clojure
;; GOOD: Force evaluation while instance is alive
(with-open [instance (vk/instance!)]
  (mapv vk/properties (vk/physical-devices instance)))
;; Everything executes before instance closes - safe!
```

## Roadmap

### Device Limits and Sparse Properties

Physical device limits and sparse memory properties are currently returned as raw LWJGL struct objects. These should be converted to idiomatic Clojure data structures.

**Limits** - Hardware capabilities (max texture sizes, descriptor counts, compute work group sizes, etc.). Currently accessible but not user-friendly.

**Sparse Properties** - Sparse memory support flags (virtual texturing, mega-textures). Currently stored but not exposed.

**Planned:**
- Convert `VkPhysicalDeviceLimits` to nested Clojure map with categorized limits
- Convert `VkPhysicalDeviceSparseProperties` to simple boolean map
- Add helper functions to check common limits (e.g., `supports-texture-size?`, `max-compute-threads`)

See [CONCEPTS.md](CONCEPTS.md) for detailed explanations of device limits and sparse resources.

### Extension-Specific Device Properties

Currently, `properties` only returns core `VkPhysicalDeviceProperties` fields (9 fields). Vulkan exposes ~500+ additional properties through extensions (ray tracing, mesh shaders, fragment shading rate, etc.).

**Current Status:** Only core properties implemented

**Planned:**
- Implement `vkGetPhysicalDeviceProperties2` with pNext chain support
- Query all available extension-specific property structures
- Add ~500+ extension property fields (ray tracing pipeline properties, mesh shader properties, acceleration structure properties, etc.)
- Provide filtered queries (e.g., `ray-tracing-properties`, `mesh-shader-properties`) to avoid overwhelming output

### Extension-Specific Device Features

Currently, `supported-features` only returns core `VkPhysicalDeviceFeatures` fields (55 boolean features). Vulkan exposes ~355+ additional features through extensions that must be queried and enabled separately.

**Current Status:** Only core features implemented (e.g., `:geometry-shader`, `:tessellation-shader`, `:multi-draw-indirect`)

**Planned:**
- Implement `vkGetPhysicalDeviceFeatures2` with pNext chain support
- Query all available extension-specific feature structures
- Add ~355+ extension feature flags (ray tracing features, mesh shader features, descriptor indexing features, etc.)
- Extend logical device creation to support enabling extension features
- Maintain kebab-case keyword conversion for all features (using `camel-snake-kebab`)

### Queue Creation Flags

Currently, queue creation hardcodes `.flags 0` (no flags). Vulkan supports `VkDeviceQueueCreateFlags` for specialized queue creation.

**Current Status:** Not yet implemented

**Available Flags:**
- `VK_DEVICE_QUEUE_CREATE_PROTECTED_BIT` - Create queues that can only access protected memory (for DRM/secure content)

**Planned:**
- Add optional `:flags` parameter to queue family specs in `logical-device!`
- Support `:protected` flag keyword for DRM video playback and secure content rendering
- Format: `{queue-family {:queue-count 1 :flags #{:protected}}}`

**Use Cases:**
- DRM video streaming (Netflix, Disney+, etc.)
- Secure media playback on mobile devices
- Hardware-enforced content protection
- Very niche - 99.9% of apps don't need this

### macOS Support

macOS does not have native Vulkan support and relies on MoltenVK, which translates Vulkan calls to Metal. MoltenVK implements a **portability subset** of Vulkan rather than the full specification.

To support macOS, the following must be implemented:

**Instance Creation Requirements:**
- Enable the `VK_KHR_portability_enumeration` instance extension
- Set the `VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR` flag in `VkInstanceCreateInfo.flags`

**Device Creation Requirements:**
- Enable the `VK_KHR_portability_subset` device extension when creating logical devices

Without these portability extensions, instance creation will fail on macOS or will not enumerate MoltenVK devices.

**Current Status:** Not yet implemented. Extension and layer support needs to be added to `create-instance` first.

### Surface Support (Graphics Rendering)

Surface support is required for rendering graphics to a window. The following functions need to be implemented on PhysicalDevice:

- `surface_support(queue_family_index, surface)` - Check if a queue family can present to your window surface
- `surface_formats(surface)` - Get compatible image formats and color spaces for the swapchain
- `surface_capabilities(surface)` - Get swapchain constraints (min/max images, transform support, etc.)
- `surface_present_modes(surface)` - Choose presentation mode (FIFO/vsync, immediate, mailbox)

**Note:** Surface support is not needed for headless compute workloads or offscreen rendering.

**Current Status:** Not yet implemented. Requires surface creation from windowing system integration.

## License

Copyright © 2026 Crinklywrappr

_EPLv1.0 is just the default for projects generated by `deps-new`: you are not_
_required to open source this project, nor are you required to use EPLv1.0!_
_Feel free to remove or change the `LICENSE` file and remove or update this_
_section of the `README.md` file!_

Distributed under the Eclipse Public License version 1.0.
