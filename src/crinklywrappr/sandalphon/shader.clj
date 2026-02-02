(ns crinklywrappr.sandalphon.shader
  "Compile-time shader compilation for Vulkan.

  This namespace provides macros that compile GLSL shaders to SPIR-V at
  macro-expansion time using shaderc (via LWJGL bindings). With AOT compilation,
  shaders are compiled once at build time and embedded in the resulting .class files.

  Example:
    (defshader my-compute
      :stage :compute
      :source \"
        #version 450
        layout(local_size_x = 64) in;
        layout(set = 0, binding = 0) buffer Data { uint data[]; };
        void main() { data[gl_GlobalInvocationID.x] *= 12; }
      \")

    ;; my-compute is now a map with :spirv ByteBuffer and reflection metadata"
  (:require [clojure.reflect :as r]
            [clojure.java.io :as io]
            [clojure.string :as sg]
            [camel-snake-kebab.core :as csk])
  (:import [org.lwjgl.util.shaderc Shaderc]
           [org.lwjgl.util.spvc Spvc SpvcReflectedResource]
           [org.lwjgl.system MemoryStack]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.net URL]))

;; ============================================================================
;; Reflection-based constant discovery
;; ============================================================================

;; shaderc_xxx_shader (excluding glsl variants) -> :xxx
(def ^:private stage->shaderc
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/ends-with? % "_shader"))
           (remove #(sg/includes? % "glsl"))
           (keep (fn [s]
                   (when-let [v (.get (.getField Shaderc s) nil)]
                     [(-> (sg/replace s #"^shaderc_(.*)_shader$" "$1")
                          (csk/->kebab-case-keyword)) v]))))
          (:members (r/type-reflect Shaderc)))))

;; shaderc_optimization_level_xxx -> :xxx
(def ^:private optimize->shaderc
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "shaderc_optimization_level_"))
           (keep (fn [s]
                   (when-let [v (.get (.getField Shaderc s) nil)]
                     [(keyword (sg/replace s "shaderc_optimization_level_" "")) v]))))
          (:members (r/type-reflect Shaderc)))))

;; shaderc_env_version_vulkan_X_Y -> :vulkan-X.Y
(def ^:private target-env->shaderc
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "shaderc_env_version_vulkan_"))
           (keep (fn [s]
                   (when-let [v (.get (.getField Shaderc s) nil)]
                     [(->> (re-seq #"\d+" s)
                           (sg/join ".")
                           (str "vulkan-")
                           keyword) v]))))
          (:members (r/type-reflect Shaderc)))))

;; ============================================================================
;; User-facing discovery functions
;; ============================================================================

(defn shader-stages
  "Returns the set of supported shader stages."
  []
  (set (keys @stage->shaderc)))

(defn optimization-levels
  "Returns the set of supported optimization levels."
  []
  (set (keys @optimize->shaderc)))

(defn target-environments
  "Returns the set of supported target Vulkan environments."
  []
  (set (keys @target-env->shaderc)))

;; ============================================================================
;; Compilable protocol (like clojure.java.io Coercions)
;; ============================================================================

(defprotocol Compilable
  "Protocol for things that can be compiled to SPIR-V.
  Implementations should return GLSL source as a string."
  (->glsl [x] "Coerces x to GLSL source string."))

(extend-protocol Compilable
  String
  (->glsl [s]
    ;; Strings with newlines are inline source; otherwise try as path
    (if (sg/includes? s "\n")
      s
      (if-let [resource (io/resource s)]
        (slurp resource)
        (let [f (io/file s)]
          (if (.exists f)
            (slurp f)
            ;; No newline and not a valid path - assume inline source
            s)))))

  java.io.File
  (->glsl [f]
    (if (.exists f)
      (slurp f)
      (throw (ex-info "Shader file not found" {:file (.getPath f)}))))

  URL
  (->glsl [url]
    (slurp url))

  java.nio.file.Path
  (->glsl [p]
    (->glsl (.toFile p))))

;; ============================================================================
;; Source Hashing
;; ============================================================================

(defn- source-hash
  "Computes SHA-256 hash of shader source for cache invalidation."
  [source]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str source) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) bytes))))

;; ============================================================================
;; GLSL Compilation
;; ============================================================================

(defn compile-glsl
  "Compiles GLSL source to SPIR-V ByteBuffer using shaderc.

  Parameters:
    source - GLSL source (String, File, URL, Path, or resource path string)
    stage  - Shader stage keyword (call (shader-stages) to see options)

  Options:
    :optimize   - Optimization level (call (optimization-levels) to see options)
                  Default: :zero
    :target-env - Target Vulkan version (call (target-environments) to see options)
    :filename   - Filename for error messages (default \"shader.glsl\")

  Returns:
    ByteBuffer containing SPIR-V bytecode (native byte order)

  Throws:
    ExceptionInfo if compilation fails, with :error containing compiler output"
  [source stage & {:keys [optimize target-env filename]
                   :or {optimize :zero
                        filename "shader.glsl"}}]

  (let [source-str (->glsl source)]

    ;; Validate stage
    (when-not (contains? @stage->shaderc stage)
      (throw (ex-info "Invalid shader stage"
                      {:stage stage
                       :valid-stages (shader-stages)})))

    ;; Validate optimization level
    (when-not (contains? @optimize->shaderc optimize)
      (throw (ex-info "Invalid optimization level"
                      {:optimize optimize
                       :valid-levels (optimization-levels)})))

    (let [compiler (Shaderc/shaderc_compiler_initialize)
          options (Shaderc/shaderc_compile_options_initialize)]
      (try
        ;; Set optimization level
        (Shaderc/shaderc_compile_options_set_optimization_level
         options (@optimize->shaderc optimize))

        ;; Set target environment if specified
        (when target-env
          (when-not (contains? @target-env->shaderc target-env)
            (throw (ex-info "Invalid target environment"
                            {:target-env target-env
                             :valid-targets (target-environments)})))
          (Shaderc/shaderc_compile_options_set_target_env
           options
           Shaderc/shaderc_target_env_vulkan
           (@target-env->shaderc target-env)))

        ;; Compile
        (let [result (Shaderc/shaderc_compile_into_spv
                      compiler
                      source-str
                      (@stage->shaderc stage)
                      filename
                      "main"
                      options)
              status (Shaderc/shaderc_result_get_compilation_status result)]

          (try
            (if (== status Shaderc/shaderc_compilation_status_success)
              ;; Success - extract SPIR-V as ByteBuffer
              (let [spirv-src (Shaderc/shaderc_result_get_bytes result)
                    spirv-buf (ByteBuffer/allocateDirect (.remaining spirv-src))]
                (.order spirv-buf (ByteOrder/nativeOrder))
                (.put spirv-buf spirv-src)
                (.flip spirv-buf)
                spirv-buf)

              ;; Failure - throw with error message
              (let [error-msg (Shaderc/shaderc_result_get_error_message result)
                    num-errors (Shaderc/shaderc_result_get_num_errors result)
                    num-warnings (Shaderc/shaderc_result_get_num_warnings result)]
                (throw (ex-info "Shader compilation failed"
                                {:stage stage
                                 :error error-msg
                                 :num-errors num-errors
                                 :num-warnings num-warnings
                                 :source (let [lines (sg/split-lines source-str)]
                                           (if (> (count lines) 30)
                                             (str (sg/join "\n" (take 30 lines)) "\n... (truncated)")
                                             source-str))}))))

            (finally
              (Shaderc/shaderc_result_release result))))

        (finally
          (Shaderc/shaderc_compile_options_release options)
          (Shaderc/shaderc_compiler_release compiler))))))

;; ============================================================================
;; ============================================================================
;; ============================================================================
;; SPIR-V Reflection (Phase 2)
;; ============================================================================

;; SPIR-V decoration constants (from SPIR-V spec, not exposed in LWJGL)
(def ^:private ^:const spv-decoration-binding 33)
(def ^:private ^:const spv-decoration-descriptor-set 34)
(def ^:private ^:const spv-execution-mode-local-size 17)

;; Resource type to keyword mapping (discovered via reflection)
(def ^:private resource-type->kw
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "SPVC_RESOURCE_TYPE_"))
           (remove #(sg/ends-with? % "UNKNOWN"))
           (keep (fn [s]
                   (when-let [v (.get (.getField Spvc s) nil)]
                     [v (-> (sg/replace s "SPVC_RESOURCE_TYPE_" "")
                            csk/->kebab-case-keyword)]))))
          (:members (r/type-reflect Spvc)))))

;; Base type to keyword mapping (discovered via reflection)
(def ^:private basetype->kw
  (delay
    (into {}
          (comp
           (map (comp str :name))
           (filter #(sg/starts-with? % "SPVC_BASETYPE_"))
           (remove #(sg/ends-with? % "UNKNOWN"))
           (keep (fn [s]
                   (when-let [v (.get (.getField Spvc s) nil)]
                     [v (-> (sg/replace s "SPVC_BASETYPE_" "")
                            csk/->kebab-case-keyword)]))))
          (:members (r/type-reflect Spvc)))))

(defn- get-type-info
  "Extracts type information from a SPVC type handle."
  [compiler type-handle]
  (let [basetype (Spvc/spvc_type_get_basetype type-handle)
        basetype-kw (@basetype->kw basetype)
        vector-size (Spvc/spvc_type_get_vector_size type-handle)
        columns (Spvc/spvc_type_get_columns type-handle)
        num-array-dims (Spvc/spvc_type_get_num_array_dimensions type-handle)
        array-dims (when (pos? num-array-dims)
                     (mapv #(Spvc/spvc_type_get_array_dimension type-handle %)
                           (range num-array-dims)))]
    (cond-> {:basetype basetype-kw}
      (> vector-size 1) (assoc :vector-size vector-size)
      (> columns 1) (assoc :columns columns)
      (seq array-dims) (assoc :array array-dims))))

(defn- get-struct-members
  "Extracts struct member information for a type."
  [stack compiler type-id type-handle]
  (let [num-members (Spvc/spvc_type_get_num_member_types type-handle)]
    (when (pos? num-members)
      (vec
       (for [i (range num-members)]
         (let [member-name (Spvc/spvc_compiler_get_member_name compiler type-id i)
               member-type-id (Spvc/spvc_type_get_member_type type-handle i)
               member-type-handle (Spvc/spvc_compiler_get_type_handle compiler member-type-id)

               ;; Get offset
               p-offset (.mallocInt stack 1)
               _ (Spvc/spvc_compiler_type_struct_member_offset compiler type-handle i p-offset)
               offset (.get p-offset 0)

               ;; Get size
               p-size (.mallocPointer stack 1)
               _ (Spvc/spvc_compiler_get_declared_struct_member_size compiler type-handle i p-size)
               size (.get p-size 0)

               type-info (get-type-info compiler member-type-handle)]
           (-> {:name member-name
                :offset offset
                :size size}
               (merge type-info))))))))

(defn reflect-spirv
  "Extracts reflection data from compiled SPIR-V bytecode.

  Returns a map with:
    :bindings       - Vector of descriptor binding maps
    :push-constants - Vector of push constant info (if any)
    :local-size     - [x y z] for compute shaders, nil for others

  Each binding contains:
    :set, :binding, :type, :name - Basic binding info
    :basetype                    - Base type (:struct, :sampled-image, etc.)
    :block-size                  - Size in bytes (for buffers)
    :members                     - Struct members (for buffers)

  Each struct member contains:
    :name, :offset, :size, :basetype
    :vector-size (if vector), :columns (if matrix), :array (if array)"
  [^ByteBuffer spirv]
  (with-open [stack (MemoryStack/stackPush)]
    (let [;; Create context
          pp-context (.mallocPointer stack 1)
          _ (when-not (== Spvc/SPVC_SUCCESS (Spvc/spvc_context_create pp-context))
              (throw (ex-info "Failed to create spvc context" {})))
          context (.get pp-context 0)]

      (try
        (let [;; Parse SPIR-V
              spirv-dup (.duplicate spirv)
              word-count (/ (.remaining spirv-dup) 4)
              spirv-int (.asIntBuffer spirv-dup)

              pp-ir (.mallocPointer stack 1)
              _ (when-not (== Spvc/SPVC_SUCCESS
                              (Spvc/spvc_context_parse_spirv context spirv-int word-count pp-ir))
                  (throw (ex-info "Failed to parse SPIR-V" {})))
              ir (.get pp-ir 0)

              ;; Create compiler (NONE backend for reflection only)
              pp-compiler (.mallocPointer stack 1)
              _ (when-not (== Spvc/SPVC_SUCCESS
                              (Spvc/spvc_context_create_compiler context
                                                                 Spvc/SPVC_BACKEND_NONE
                                                                 ir
                                                                 Spvc/SPVC_CAPTURE_MODE_TAKE_OWNERSHIP
                                                                 pp-compiler))
                  (throw (ex-info "Failed to create compiler" {})))
              compiler (.get pp-compiler 0)

              ;; Create shader resources
              pp-resources (.mallocPointer stack 1)
              _ (when-not (== Spvc/SPVC_SUCCESS
                              (Spvc/spvc_compiler_create_shader_resources compiler pp-resources))
                  (throw (ex-info "Failed to create shader resources" {})))
              resources (.get pp-resources 0)

              ;; Helper to get resources of a type with full reflection
              get-resources
              (fn [resource-type]
                (let [pp-list (.mallocPointer stack 1)
                      p-count (.mallocPointer stack 1)]
                  (when (== Spvc/SPVC_SUCCESS
                            (Spvc/spvc_resources_get_resource_list_for_type
                             resources resource-type pp-list p-count))
                    (let [count (.get p-count 0)
                          list-ptr (.get pp-list 0)]
                      (when (pos? count)
                        (let [res-buf (SpvcReflectedResource/create list-ptr (int count))]
                          (doall
                           (for [i (range count)]
                             (let [res (.get res-buf (int i))
                                   id (.id res)
                                   base-type-id (.base_type_id res)
                                   type-handle (Spvc/spvc_compiler_get_type_handle compiler base-type-id)
                                   type-info (get-type-info compiler type-handle)

                                   ;; Get struct size if applicable
                                   block-size (when (= :struct (:basetype type-info))
                                                (let [p-size (.mallocPointer stack 1)]
                                                  (when (== Spvc/SPVC_SUCCESS
                                                            (Spvc/spvc_compiler_get_declared_struct_size
                                                             compiler type-handle p-size))
                                                    (.get p-size 0))))

                                   ;; Get struct members if applicable
                                   members (when (= :struct (:basetype type-info))
                                             (get-struct-members stack compiler base-type-id type-handle))]

                               (cond-> {:name (Spvc/spvc_compiler_get_name compiler id)
                                        :set (Spvc/spvc_compiler_get_decoration
                                              compiler id spv-decoration-descriptor-set)
                                        :binding (Spvc/spvc_compiler_get_decoration
                                                  compiler id spv-decoration-binding)
                                        :type (@resource-type->kw resource-type)}
                                 (:basetype type-info) (assoc :basetype (:basetype type-info))
                                 block-size (assoc :block-size block-size)
                                 (seq members) (assoc :members members)))))))))))

              ;; Collect bindings from all resource types except push constants
              bindings (->> (keys @resource-type->kw)
                            (remove #(= :push-constant (@resource-type->kw %)))
                            (mapcat get-resources)
                            (sort-by (juxt :set :binding))
                            vec)

              ;; Get push constants
              push-constant-type (some (fn [[k v]] (when (= v :push-constant) k))
                                       @resource-type->kw)
              push-constants (when push-constant-type
                               (->> (get-resources push-constant-type)
                                    (map #(dissoc % :set :binding :type))
                                    vec))

              ;; Get local size for compute shaders
              local-size (let [x (Spvc/spvc_compiler_get_execution_mode_argument_by_index
                                  compiler spv-execution-mode-local-size 0)
                              y (Spvc/spvc_compiler_get_execution_mode_argument_by_index
                                  compiler spv-execution-mode-local-size 1)
                              z (Spvc/spvc_compiler_get_execution_mode_argument_by_index
                                  compiler spv-execution-mode-local-size 2)]
                          (when (and (pos? x) (pos? y) (pos? z))
                            [x y z]))]

          (cond-> {:bindings bindings}
            (seq push-constants) (assoc :push-constants push-constants)
            local-size (assoc :local-size local-size)))

        (finally
          (Spvc/spvc_context_destroy context))))))

;; Shader Macro
;; ============================================================================

(defn- bytebuffer->vec
  "Converts a ByteBuffer to a vector of bytes for embedding in macro output."
  [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (vec arr)))

(defmacro defshader
  "Defines a shader, compiling GLSL to SPIR-V at macro-expansion time.

  The shader is compiled using shaderc (via LWJGL). With AOT compilation,
  this happens once at build time and the SPIR-V bytes are embedded in the
  compiled .class files.

  Parameters:
    name - Symbol to def the shader as

  Options:
    :stage   - Shader stage (required): call (shader-stages) to see options
    :source  - GLSL source: inline string, file path, or resource path
    :optimize - Optimization level: call (optimization-levels) to see options
    :target-env - Target Vulkan version: call (target-environments) to see options

  The resulting var contains a map with:
    :name        - String name of the shader
    :stage       - Shader stage keyword
    :spirv       - ByteBuffer of SPIR-V bytecode
    :source-hash - SHA-256 hash of source (for cache invalidation)
    :bindings    - Vector of descriptor bindings from reflection
    :push-constants - Push constant info (if any)
    :local-size  - [x y z] for compute shaders (if applicable)

  Example:
    (defshader my-compute
      :stage :compute
      :source \"
        #version 450
        layout(local_size_x = 64) in;
        layout(set = 0, binding = 0) buffer Data { uint data[]; };
        void main() { data[gl_GlobalInvocationID.x] *= 12; }
      \")

    ;; Or from a file:
    (defshader my-vertex
      :stage :vertex
      :source \"shaders/triangle.vert\")"
  [name & {:keys [stage source optimize target-env]
           :or {optimize :zero}}]

  ;; Validation at macro-expansion time
  (when-not stage
    (throw (ex-info "Shader stage is required"
                    {:shader name
                     :hint "Add :stage :compute, :vertex, :fragment, etc."})))

  (when-not source
    (throw (ex-info "Shader source is required"
                    {:shader name
                     :hint "Add :source with inline GLSL or file path"})))

  ;; Resolve and compile at macro-expansion time
  (let [source-str (->glsl source)
        spirv (compile-glsl source-str stage
                            :optimize optimize
                            :target-env target-env
                            :filename (str name ".glsl"))
        spirv-vec (bytebuffer->vec spirv)
        hash (source-hash source-str)
        reflection (reflect-spirv spirv)
        push-constants (:push-constants reflection)
        local-size (:local-size reflection)]

    ;; Emit the def - ByteBuffer is recreated at runtime from the embedded bytes
    `(def ~name
       (cond-> {:name ~(str name)
                :stage ~stage
                :spirv (let [arr# (byte-array ~spirv-vec)
                             buf# (ByteBuffer/allocateDirect (alength arr#))]
                         (.order buf# (ByteOrder/nativeOrder))
                         (.put buf# arr#)
                         (.flip buf#)
                         buf#)
                :source-hash ~hash
                :bindings ~(:bindings reflection)}
         ;; push-constants and local-size are either nil or non-empty (never empty collections)
         ~(some? push-constants) (assoc :push-constants ~push-constants)
         ~(some? local-size) (assoc :local-size ~local-size)))))

(defn load-spirv
  "Loads pre-compiled SPIR-V from a file, resource, or URL.

  Use this for shaders compiled outside of the defshader macro,
  such as shaders from external tools or third-party libraries.

  Parameters:
    source - File, URL, Path, or resource path string pointing to .spv file
    stage  - Shader stage keyword

  Returns:
    Map with :spirv ByteBuffer, :stage, :name, and reflection data
    (same shape as defshader output)"
  [source stage]
  (let [spirv-bytes (cond
                      (instance? java.io.File source)
                      (Files/readAllBytes (.toPath ^java.io.File source))

                      (instance? java.nio.file.Path source)
                      (Files/readAllBytes ^java.nio.file.Path source)

                      (instance? URL source)
                      (with-open [in (io/input-stream source)]
                        (.readAllBytes in))

                      (string? source)
                      (if-let [resource (io/resource source)]
                        (with-open [in (io/input-stream resource)]
                          (.readAllBytes in))
                        (Files/readAllBytes (.toPath (io/file source))))

                      :else
                      (throw (ex-info "Cannot load SPIR-V from source"
                                      {:source source
                                       :type (type source)})))]
    ;; Validate SPIR-V magic number
    (when (or (< (alength spirv-bytes) 4)
              (not= (bit-or (bit-and (aget spirv-bytes 0) 0xFF)
                            (bit-shift-left (bit-and (aget spirv-bytes 1) 0xFF) 8)
                            (bit-shift-left (bit-and (aget spirv-bytes 2) 0xFF) 16)
                            (bit-shift-left (bit-and (aget spirv-bytes 3) 0xFF) 24))
                    0x07230203))
      (throw (ex-info "Invalid SPIR-V: bad magic number"
                      {:source source
                       :expected "0x07230203"
                       :got (when (>= (alength spirv-bytes) 4)
                              (format "0x%02X%02X%02X%02X"
                                      (aget spirv-bytes 3)
                                      (aget spirv-bytes 2)
                                      (aget spirv-bytes 1)
                                      (aget spirv-bytes 0)))})))
    (let [buf (ByteBuffer/allocateDirect (alength spirv-bytes))]
      (.order buf (ByteOrder/nativeOrder))
      (.put buf spirv-bytes)
      (.flip buf)
      (let [reflection (reflect-spirv buf)]
        {:name (str source)
         :stage stage
         :spirv buf
         :bindings (:bindings reflection)
         :push-constants (:push-constants reflection)
         :local-size (:local-size reflection)}))))
