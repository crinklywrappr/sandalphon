# Descriptor Set Layout Design

This document explains the decisions required when designing descriptor set layouts, and what application-level knowledge informs those choices.

## What a Descriptor Set Layout Defines

A `VkDescriptorSetLayout` specifies:

- Which binding numbers exist (0, 1, 2, ...)
- The descriptor type at each binding (uniform buffer, storage buffer, sampled image, etc.)
- Which shader stages can access each binding
- Array count for each binding (1 for single descriptor, N for arrays)
- Optional flags for advanced features

This is a *template* that describes the shape of descriptor sets. Actual descriptor sets are allocated from pools and populated with concrete resources (buffers, images).


## What Are Samplers?

Samplers control how textures are read (sampled) in shaders. When you access a texture at UV coordinates, the sampler determines:

**Filtering** - What happens when you sample between pixels?
- `nearest` - Pick closest pixel (blocky, retro look)
- `linear` - Blend neighboring pixels (smooth)

**Mipmapping** - Which detail level to use for distant objects?
- Textures store pre-computed smaller versions (mipmaps)
- Sampler picks appropriate level based on distance/angle

**Addressing/Wrapping** - What happens outside 0-1 UV range?
- `repeat` - Tile the texture (UV 1.5 → 0.5)
- `clamp-to-edge` - Stick to edge color
- `mirrored-repeat` - Tile with alternating mirrors

**Anisotropic filtering** - Sharpen textures viewed at angles

```glsl
// In shader, sampler + texture work together:
vec4 color = texture(mySampler, uv);  // sampler controls HOW to read
```

**Why separate from textures?**

One sampler can be reused with many textures. You might have:
- One "pixel art" sampler (nearest, no mipmap)
- One "smooth" sampler (linear, trilinear mipmaps)  
- One "UI" sampler (clamp to edge)

Then bind different textures with appropriate samplers.

**Combined vs separate:**
- `combined-image-sampler` - Texture and sampler bundled together
- `sampled-image` + `sampler` - Separate, mix and match

For simple cases, combined is easier. Separate gives more flexibility with fewer total objects.

## Decisions You Must Make

### 1. Binding Assignment

Each resource needs a binding number. Shaders reference bindings by number:

```glsl
layout(set = 0, binding = 0) uniform Camera { ... };
layout(set = 0, binding = 1) buffer Instances { ... };
layout(set = 0, binding = 2) uniform sampler2D textures[16];
```

**Considerations:**

- Binding numbers don't need to be contiguous (0, 1, 5, 10 is valid)
- Gaps may waste memory in some implementations
- Grouping related bindings helps readability
- Reserved bindings for future use can avoid layout changes

**Reflection provides:** The binding numbers declared in shaders
**You must decide:** Whether to accept shader declarations or enforce a convention

### 2. Descriptor Types

Each binding has exactly one descriptor type:

| Type | Use Case |
|------|----------|
| `uniform-buffer` | Small, read-only data (matrices, parameters) |
| `storage-buffer` | Large or read-write data (vertex buffers, compute I/O) |
| `uniform-buffer-dynamic` | Uniform buffer with runtime offset |
| `storage-buffer-dynamic` | Storage buffer with runtime offset |
| `sampled-image` | Textures for sampling |
| `storage-image` | Images for compute read/write |
| `sampler` | Separate sampler object |
| `combined-image-sampler` | Texture + sampler together |
| `input-attachment` | Framebuffer input for subpasses |

**Reflection provides:** The type declared in each shader
**You must decide:**

- Whether to use dynamic buffer variants (enables offset changes without descriptor updates)
- Whether to use combined image samplers vs. separate (affects flexibility vs. descriptor count)

### 3. Stage Visibility

Each binding specifies which shader stages can access it:

```clojure
{:binding 0 :type :uniform-buffer :stages #{:vertex}}           ; vertex only
{:binding 1 :type :uniform-buffer :stages #{:vertex :fragment}} ; both stages
{:binding 2 :type :sampled-image :stages #{:fragment}}          ; fragment only
```

**Why it matters:**

- Narrower visibility may enable driver optimizations
- Some implementations validate that only declared stages access bindings
- Overly broad visibility (e.g., all stages) works but may be suboptimal

**Reflection provides:** The stage where each binding is declared
**You must decide:** When multiple shaders share a layout, union their stage requirements

### 4. Array Counts

Bindings can be single descriptors or arrays:

```glsl
layout(binding = 0) uniform sampler2D single_texture;      // count = 1
layout(binding = 1) uniform sampler2D texture_array[16];   // count = 16
layout(binding = 2) uniform sampler2D bindless_textures[]; // variable count
```

**Fixed arrays:**
- Count is part of the layout
- All elements must be valid when bound (unless `PARTIALLY_BOUND` flag)
- Good for known quantities (shadow map cascades, material layers)

**Variable-count arrays (descriptor indexing):**
- Final binding can have runtime-determined count
- Requires `VARIABLE_DESCRIPTOR_COUNT` flag
- Enables bindless patterns

**Reflection provides:** Array sizes declared in shaders (or 0 for unsized)
**You must decide:** Actual counts for variable arrays, whether to use bindless

### 5. Binding Flags

Advanced descriptor features require per-binding flags:

| Flag | Effect |
|------|--------|
| `UPDATE_AFTER_BIND` | Descriptors can be updated after binding to command buffer |
| `PARTIALLY_BOUND` | Not all array elements need valid descriptors |
| `VARIABLE_DESCRIPTOR_COUNT` | Final binding has runtime-determined count |

**When you need them:**

- Bindless rendering requires `PARTIALLY_BOUND` and often `UPDATE_AFTER_BIND`
- Streaming texture systems need `UPDATE_AFTER_BIND`
- GPU-driven rendering often uses all three

**Reflection provides:** Nothing—these are usage patterns, not shader declarations
**You must decide:** Based on how your application updates and binds descriptors

### 6. Immutable Samplers

Samplers can be baked into the layout itself:

```clojure
{:binding 0
 :type :combined-image-sampler
 :stages #{:fragment}
 :immutable-samplers [linear-sampler linear-sampler linear-sampler]}
```

**Benefits:**
- No need to write sampler to descriptor set
- Sampler state known at pipeline compile time (potential optimization)
- Reduces descriptor pool requirements

**Drawbacks:**
- Can't change sampler without new layout
- Less flexible

**Reflection provides:** Nothing
**You must decide:** Whether specific samplers should be immutable

## Common Patterns

### Pattern 1: Single Set Per Shader

Simplest approach—each shader gets its own layout:

```clojure
(def compute-layout
  {:bindings [{:binding 0 :type :storage-buffer :stages #{:compute}}
              {:binding 1 :type :storage-buffer :stages #{:compute}}]})
```

**Pros:** Simple, matches shader exactly
**Cons:** No sharing between pipelines

### Pattern 2: Layered Sets by Frequency

Organize sets by how often they change:

```clojure
;; Set 0: Global (bind once per frame)
(def global-layout
  {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment}}  ; camera
              {:binding 1 :type :uniform-buffer :stages #{:fragment}}]})       ; lighting

;; Set 1: Material (bind per material)
(def material-layout
  {:bindings [{:binding 0 :type :sampled-image :stages #{:fragment}}   ; albedo
              {:binding 1 :type :sampled-image :stages #{:fragment}}   ; normal
              {:binding 2 :type :uniform-buffer :stages #{:fragment}}]}) ; params

;; Set 2: Object (bind per draw)
(def object-layout
  {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex}}]}) ; transform
```

**Pros:** Minimizes rebinding, efficient for scene rendering
**Cons:** Requires planning, shaders must follow convention

### Pattern 3: Bindless

All resources in large arrays, index dynamically:

```clojure
(def bindless-layout
  {:bindings [{:binding 0 :type :sampled-image :stages #{:fragment}
               :count 4096
               :flags #{:partially-bound :update-after-bind}}
              {:binding 1 :type :storage-buffer :stages #{:vertex :fragment}
               :count 1}]}) ; material buffer with texture indices
```

**Pros:** Bind once, GPU-driven rendering possible
**Cons:** Requires descriptor indexing support, more complex

### Pattern 4: Superset Layouts

Design layouts that work for multiple shaders:

```clojure
;; Shader A uses bindings 0, 1
;; Shader B uses bindings 0, 1, 2
;; Create layout with all three:
(def shared-layout
  {:bindings [{:binding 0 :type :uniform-buffer :stages #{:vertex :fragment}}
              {:binding 1 :type :storage-buffer :stages #{:compute :vertex}}
              {:binding 2 :type :sampled-image :stages #{:fragment}}]})

;; Shader A ignores binding 2, but layout is compatible
;; Both pipelines can use same descriptor sets
```

**Pros:** Enables pipeline switching without rebinding
**Cons:** May include unused bindings, wastes some descriptor space

## Relationship to Pipeline Layouts

Descriptor set layouts define individual sets. Pipeline layouts combine them:

```
Descriptor Set Layout 0: {binding 0, binding 1}
Descriptor Set Layout 1: {binding 0}
Descriptor Set Layout 2: {binding 0, binding 1, binding 2}
                    ↓
Pipeline Layout: [Layout 0, Layout 1, Layout 2] + push constants
                    ↓
Pipeline: uses Pipeline Layout
```

The descriptor set layout is the unit of sharing. Two pipelines with identical set 0 layouts can share set 0 descriptor sets.

## Validation Against Shaders

After designing layouts manually, validate they match shader requirements:

```clojure
(defn validate-layout [layout shader set-index]
  (let [shader-bindings (->> (:bindings shader)
                             (filter #(= (:set %) set-index)))]
    (doseq [{:keys [binding type]} shader-bindings]
      (let [layout-binding (first (filter #(= (:binding %) binding)
                                          (:bindings layout)))]
        (assert layout-binding
                (str "Missing binding " binding))
        (assert (= (:type layout-binding) type)
                (str "Type mismatch at binding " binding))))))
```

This catches mismatches between your layout design and shader declarations.

## Summary

| Decision | Reflection Helps | Application Knowledge Needed |
|----------|------------------|------------------------------|
| Binding numbers | Yes | Convention enforcement |
| Descriptor types | Yes | Dynamic buffer choice |
| Stage visibility | Partial | Stage unions for shared layouts |
| Array counts | Partial | Runtime counts, bindless sizing |
| Binding flags | No | Update patterns, bindless needs |
| Immutable samplers | No | Whether samplers vary |

Start simple (one layout per shader), then optimize based on profiling. The most impactful optimization is usually organizing sets by update frequency.
