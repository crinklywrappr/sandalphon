# Vulkan/Vulkano Core Concepts

## Queue & Queue Families

**Queue** = GPU's execution pipeline. Your app submits command buffers to queues, and the GPU processes them.

**Queue Family** = Group of queues with the same capabilities. Each physical device has one or more queue families, each supporting different operations:
- **Graphics** - Can draw triangles, run shaders, etc.
- **Compute** - Can run compute shaders (GPU calculations without rendering)
- **Transfer** - Can copy memory between buffers/images
- **Sparse binding** - Advanced memory management

Most GPUs have multiple queue families. For example:
- Family 0: Graphics + Compute + Transfer (the "universal" queue)
- Family 1: Compute + Transfer only
- Family 2: Transfer only (DMA engine)

You query `queue_family_properties()` to find which families support what, then create queues from the families you need.

## Surface

**Surface** = Abstract representation of "where pixels go" for presentation.

Think of it as the bridge between Vulkan (cross-platform) and your OS's windowing system:
- On Linux with X11/Wayland: Surface wraps the window handle
- On Windows: Surface wraps HWND
- On macOS: Surface wraps NSView

The surface is **not** part of core Vulkan—it's from the `VK_KHR_surface` extension. You need it for:
1. Checking if a queue family can **present** (show images on screen)
2. Querying supported formats/color spaces
3. Creating a **swapchain** (the image buffers that rotate for display)

**You don't need surfaces** for:
- Headless compute (calculations only)
- Offscreen rendering (render to image, save to file)

## Display

**Display** = Physical monitor/screen hardware, accessed through `VK_KHR_display` extension.

This is for **direct display access** without a windowing system—useful for:
- Fullscreen applications on embedded systems
- Kiosks, dedicated rendering machines
- Game consoles (direct-to-framebuffer)

Most desktop apps use **Surface** (window-based) instead of Display (direct hardware). Display mode bypasses X11/Wayland/Win32 entirely.

## Memory

**Memory** in Vulkan is explicit and manual—you manage everything.

### Memory Types

Physical devices expose multiple **memory types**, each with different properties:
- **Device-local** - Fast GPU memory (VRAM), not CPU-accessible
- **Host-visible** - CPU can read/write (usually system RAM or resizable BAR)
- **Host-coherent** - CPU writes immediately visible to GPU (no manual flushing)
- **Host-cached** - CPU reads are fast (cached in CPU cache)

Query `memory_properties()` to discover:
- How many memory types exist
- What properties each type has
- Memory heaps (pools) they draw from

### Memory Heaps

Physical pools of memory:
- Heap 0: Device-local (VRAM) - fast, large
- Heap 1: Host-visible (system RAM) - slower for GPU, CPU accessible

### Workflow

1. Create a buffer/image → Vulkan tells you memory requirements (size, alignment, compatible memory type bits)
2. Choose a memory type that satisfies requirements + your needs (fast GPU access? CPU mapping?)
3. Allocate memory from that type
4. Bind memory to buffer/image

**Example:**
- Vertex buffer for rendering: Use device-local (fast GPU reads)
- Staging buffer for uploading textures: Use host-visible + host-coherent (CPU writes, then GPU copies)
- Readback buffer for screenshots: Use host-visible + host-cached (GPU writes, CPU reads)

## How They Connect

### Typical Graphics Flow

1. Create **Surface** from your window
2. Query physical device's **queue families** → find one with graphics + present support for that surface
3. Query **memory properties** → understand available memory types
4. Create logical device with queues from selected families
5. Allocate **memory** for buffers/images from appropriate memory types
6. Create swapchain from surface (using surface formats/capabilities)
7. Submit draw commands to **graphics queue**
8. Present rendered images to **surface** via **present queue**

### Typical Compute Flow

1. Query **queue families** → find one with compute support
2. Query **memory properties**
3. Create logical device with compute queue
4. Allocate **memory** for compute buffers
5. Submit compute commands to **compute queue**
6. No surface/display needed!

## Device Limits

**Device Limits** = Hardware capabilities and constraints of your GPU.

Every GPU has different limits based on its architecture, generation, and vendor. Before you create resources (textures, buffers, descriptor sets), you need to check if your GPU supports what you're trying to do.

### Why Limits Matter

Violating limits causes:
- Validation errors during development
- Undefined behavior or crashes in production
- Your app working on one GPU but failing on another

Always query limits and design your app to work within them, or provide fallback paths.

### Common Limit Categories

**Texture/Image Limits:**
- `maxImageDimension1D/2D/3D` - Maximum texture size (e.g., 16384x16384 for modern GPUs)
- `maxImageArrayLayers` - Max texture array size (e.g., 2048 layers)
- `maxFramebufferWidth/Height` - Render target size limits

**Buffer Limits:**
- `maxUniformBufferRange` - Max size of uniform buffer (often 64KB - 256KB)
- `maxStorageBufferRange` - Max size of storage buffer (often gigabytes)
- `maxPushConstantsSize` - Max push constant size (typically 128-256 bytes)

**Compute Limits:**
- `maxComputeWorkGroupCount[X/Y/Z]` - Max work groups you can dispatch (often 65535 per axis)
- `maxComputeWorkGroupSize[X/Y/Z]` - Max threads per work group (e.g., 1024x1024x64)
- `maxComputeSharedMemorySize` - Shared memory per work group (typically 32KB - 48KB)

**Descriptor Limits:**
- `maxBoundDescriptorSets` - Max descriptor sets bound at once (typically 4-8)
- `maxPerStageDescriptorSamplers` - Max samplers per shader stage
- `maxPerStageDescriptorUniformBuffers` - Max uniform buffers per stage
- `maxDescriptorSetSamplers` - Total samplers across all stages

**Memory Limits:**
- `maxMemoryAllocationCount` - Total number of allocations (often 4096)
- `bufferImageGranularity` - Alignment between buffers and images in same memory

### Practical Examples

**Check texture size before loading:**
```
Your game wants to load an 8K texture (7680x4320).
→ Query maxImageDimension2D
→ If < 7680, downscale or tile the texture
→ Otherwise, load directly
```

**Check work group size for compute:**
```
Your ML model wants 32x32 threads per work group.
→ Query maxComputeWorkGroupSize
→ If X or Y < 32, use smaller work groups (16x16)
→ Dispatch more work groups to compensate
```

**Check descriptor set limits:**
```
Your renderer wants 1000 unique textures.
→ Query maxPerStageDescriptorSamplers (e.g., 16)
→ Use bindless texturing or texture arrays
→ Or implement a descriptor caching system
```

### Integration GPU Strategy

Many laptops have **integrated GPUs** with tighter limits than discrete GPUs:
- Smaller max texture sizes (e.g., 8192 vs 16384)
- Fewer descriptor bindings
- Less shared memory

Always test on lower-end hardware and design within conservative limits, or provide quality settings to scale down.

## Sparse Resources

**Sparse Resources** = Virtual memory for GPU resources - allocate huge virtual textures/buffers, only commit physical memory for the parts you use.

Think of it like `mmap` on Linux or virtual memory on CPU—you reserve address space but don't pay for physical memory until you actually access pages.

### What Problem Does This Solve?

**Traditional (Dense) Resources:**
- Create a 16K x 16K texture → Vulkan immediately allocates ~1GB of VRAM
- Even if you only use 10% of the texture, you pay for 100%
- Running out of VRAM? Can't load the texture at all

**Sparse Resources:**
- Reserve a 16K x 16K texture → Allocates 0 bytes of VRAM initially
- Bind memory only to the tiles you're rendering (e.g., 2K x 2K visible area)
- As camera moves, unbind old tiles, bind new tiles
- Total memory usage: only what's visible (~100MB instead of 1GB)

### Use Cases

**Mega-Textures (Games):**
- Open-world games with massive terrain
- Reserve 32K x 32K texture for entire world
- Stream in only visible terrain tiles as player moves
- Never load the whole texture into VRAM

**Virtual Texturing:**
- High-resolution photo viewers (gigapixel images)
- Reserve huge virtual texture
- Load high-res tiles for zoomed area on demand
- Unload tiles when zoomed out

**Sparse Buffers:**
- Large datasets for compute (scientific computing, ML)
- Reserve terabyte-sized buffer
- Commit memory only for active data
- Page in/out data as computation progresses

**Procedural Generation:**
- Minecraft-style voxel worlds
- Reserve huge sparse 3D texture for entire world
- Generate and commit only loaded chunks
- Unload distant chunks to save memory

### Sparse Properties

Not all GPUs support all sparse features. Query `VkPhysicalDeviceSparseProperties` to check:

- `residencyStandard2DBlockShape` - Sparse 2D textures supported
- `residencyStandard2DMultisampleBlockShape` - Sparse MSAA textures supported
- `residencyStandard3DBlockShape` - Sparse 3D textures/voxels supported
- `residencyAlignedMipSize` - Sparse mipmapped textures supported
- `residencyNonResidentStrict` - Unbound regions read as zero (strict)

### How Sparse Resources Work

1. **Create sparse resource** - Mark texture/buffer as sparse during creation
2. **Query memory requirements** - Find tile size (typically 64KB blocks)
3. **Allocate memory** - Allocate physical memory for tiles you need
4. **Bind memory to tiles** - Use sparse binding queue to map memory to specific tiles
5. **Unbind when done** - Free memory from tiles, reuse for other tiles

### Limitations

**Not all GPUs support sparse:**
- Integrated GPUs often don't support it
- Check `residency*` properties before using

**Sparse binding requires special queue:**
- Queue family must have `sparse-binding` capability
- Binding/unbinding requires queue operations (not instant)

**Tile granularity:**
- Can't bind arbitrary byte ranges
- Must work with tile boundaries (typically 64KB blocks)

**Memory management complexity:**
- You must track which tiles are resident
- Accessing non-resident tile → undefined behavior (or zero reads if strict)
- Need streaming system to load/unload tiles
