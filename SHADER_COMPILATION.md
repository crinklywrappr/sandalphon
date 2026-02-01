# Shader Compilation Design

This document outlines a macro-based shader compilation system for Sandalphon that mirrors Rust/vulkano's compile-time shader processing.

## Current Status

**Phase 1 is complete.** The `defshader` macro compiles GLSL to SPIR-V at macro-expansion time using shaderc (via LWJGL bindings). With AOT compilation, shaders are compiled once at build time and embedded in .class files.

## Overview

Vulkan requires shaders in SPIR-V format (binary bytecode). The typical workflow is:

1. Write shaders in GLSL or HLSL
2. Compile to SPIR-V using `glslc` or `glslangValidator`
3. Load SPIR-V bytes at runtime
4. Create `VkShaderModule` from the bytes
5. Use reflection data to set up descriptor layouts and pipeline layouts

Vulkano automates steps 2-5 using Rust procedural macros. We can achieve the same in Clojure.

## API

### Basic Usage

```clojure
(ns my-app.shaders
  (:require [crinklywrappr.sandalphon.shader :refer [defshader]]))

(defshader multiply-compute
  :stage :compute
  :source "
    #version 450

    layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

    layout(set = 0, binding = 0) buffer Data {
        uint data[];
    } buf;

    void main() {
        uint idx = gl_GlobalInvocationID.x;
        buf.data[idx] *= 12;
    }
  ")
```

### What `defshader` Produces

The macro expands to a def containing:

```clojure
(def multiply-compute
  {:name        "multiply-compute"
   :stage       :compute
   :spirv       #<ByteBuffer ...>   ;; SPIR-V bytecode (native byte order)
   :source-hash "abc123..."})       ;; SHA-256 for cache invalidation
```

### File-based Shaders

For shaders in separate files (classpath resources or filesystem):

```clojure
(defshader my-shader
  :stage :vertex
  :source "shaders/triangle.vert")  ;; Resolved via io/resource or io/file
```

### Options

- `:stage` - Required. Shader stage (`:compute`, `:vertex`, `:fragment`, `:geometry`, `:tess-control`, `:tess-evaluation`)
- `:source` - Required. Inline GLSL string, resource path, or file path
- `:optimize` - Optimization level (`:zero`, `:size`, `:performance`). Default: `:zero`
- `:target-env` - Target Vulkan version (`:vulkan-1.0`, `:vulkan-1.1`, etc.)

### Discovery Functions

```clojure
(shader-stages)          ;; => #{:compute :vertex :fragment ...}
(optimization-levels)    ;; => #{:zero :size :performance}
(target-environments)    ;; => #{:vulkan-1.0 :vulkan-1.1 ...}
```

### Loading Pre-compiled SPIR-V

For shaders compiled externally:

```clojure
(load-spirv "path/to/shader.spv" :compute)
;; => {:name "path/to/shader.spv" :stage :compute :spirv #<ByteBuffer>}
```

## Implementation

### Macro Expansion Flow

```
┌─────────────────┐
│  defshader      │
│  macro invoked  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Resolve source  │
│ (Compilable)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Compile via     │
│ shaderc (LWJGL) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Convert to byte │
│ vector for emit │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Emit def with   │
│ ByteBuffer init │
└─────────────────┘
```

### Compile-Time vs Runtime

| Scenario | When Compilation Happens |
|----------|-------------------------|
| REPL development | Each time form is evaluated |
| AOT compilation | Once at compile time, embedded in .class |
| uberjar | Embedded in jar, no runtime compilation |

This matches vulkano's behavior: compile-time in production, on-demand during development.

### Compilable Protocol

The `Compilable` protocol (similar to `clojure.java.io/Coercions`) handles source resolution:

```clojure
(defprotocol Compilable
  (->glsl [x] "Coerces x to GLSL source string."))

;; Implementations for String, File, URL, Path
```

- Strings with newlines are treated as inline source
- Strings without newlines try: classpath resource → filesystem → inline fallback
- Files, URLs, and Paths are read directly

## SPIR-V Reflection (Phase 2 - Future)

### Option 1: LWJGL SPIRV-Cross (Recommended)

LWJGL includes bindings to SPIRV-Cross via `lwjgl-spvc`:

```clojure
;; deps.edn
{org.lwjgl/lwjgl-spvc {:mvn/version "3.3.6"}
 org.lwjgl/lwjgl-spvc$natives-linux {:mvn/version "3.3.6"}}
```

SPIRV-Cross can:
- Extract descriptor bindings (uniforms, storage buffers, samplers)
- Extract push constant ranges
- Extract input/output variables
- Extract compute local sizes
- Cross-compile to other shading languages

### Option 2: Manual SPIR-V Parsing

SPIR-V is a well-documented binary format. Key opcodes for reflection:

| Opcode | Purpose |
|--------|---------|
| `OpDecorate` | Binding, set, location decorations |
| `OpMemberDecorate` | Struct member decorations |
| `OpVariable` | Variable declarations |
| `OpTypeStruct` | Struct type definitions |
| `OpExecutionMode` | Compute local size |

The format is straightforward:
- Little-endian 32-bit words
- Magic number: `0x07230203`
- Header: version, generator, bound, schema
- Instructions: opcode + word count in first word, then operands

## Integration with Pipeline Creation (Phase 3 - Future)

The shader metadata will enable automatic pipeline layout creation:

```clojure
(defn create-compute-pipeline [device shader]
  (let [{:keys [spirv bindings local-size]} shader
        ;; Auto-generate descriptor set layout from reflection
        descriptor-layout (create-descriptor-set-layout device bindings)
        ;; Auto-generate pipeline layout
        pipeline-layout (create-pipeline-layout device [descriptor-layout])
        ;; Create shader module
        shader-module (create-shader-module device spirv)]
    
    (create-compute-pipeline device
      :shader-module shader-module
      :layout pipeline-layout)))
```

## Error Handling

Shader compilation errors at macro-expansion time:

```clojure
(defshader broken-shader
  :stage :compute
  :source "
    #version 450
    void main() {
        undefined_function();  // Error!
    }
  ")

;; CompilerException: Shader compilation failed
;;   Stage: :compute
;;   Error: shader.glsl:4: error: 'undefined_function' : no matching overloaded function found
;;   Source: (first 30 lines...)
```

This gives immediate feedback during development, just like Rust.

## Dependencies

### Required (Phase 1 - Complete)

Already included in Sandalphon:

```clojure
;; deps.edn
{org.lwjgl/lwjgl-shaderc {:mvn/version "3.3.6"}
 org.lwjgl/lwjgl-shaderc$natives-linux {:mvn/version "3.3.6"}}
```

### Optional (Future Phases)

- `lwjgl-spvc` - For SPIR-V reflection via SPIRV-Cross (Phase 2)

## Roadmap

1. **Phase 1: Basic macro** - ✅ Complete
   - Compile GLSL to SPIR-V at macro-expansion time
   - Embed SPIR-V bytes in compiled .class files
   - Support inline source, file paths, and classpath resources
   - Configurable optimization level and target environment
   - Comprehensive test coverage

2. **Phase 2: Reflection** - Parse SPIR-V for bindings using lwjgl-spvc
   - Extract descriptor bindings (set, binding, type)
   - Extract push constant ranges
   - Extract compute local workgroup size

3. **Phase 3: Auto-layout** - Generate descriptor/pipeline layouts from reflection
   - Auto-create descriptor set layouts
   - Auto-create pipeline layouts
   - Reduce boilerplate for common patterns

4. **Phase 4: Caching** - Cache compiled SPIR-V for faster builds
   - Use source-hash for cache invalidation
   - Optional disk cache for large projects

5. **Phase 5: Hot reload** - Watch shader files and recompile in dev mode
   - File watcher integration
   - Dynamic shader module recreation

## References

- [SPIR-V Specification](https://registry.khronos.org/SPIR-V/specs/unified1/SPIRV.html)
- [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross)
- [shaderc](https://github.com/google/shaderc)
- [Vulkano shader macro](https://github.com/vulkano-rs/vulkano/tree/master/vulkano-shaders)
