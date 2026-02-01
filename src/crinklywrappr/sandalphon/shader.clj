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

    ;; my-compute is now a map with :spirv ByteBuffer and metadata"
  (:require [clojure.reflect :as r]
            [clojure.java.io :as io]
            [clojure.string :as sg]
            [camel-snake-kebab.core :as csk])
  (:import [org.lwjgl.util.shaderc Shaderc]
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
        hash (source-hash source-str)]

    ;; Emit the def - ByteBuffer is recreated at runtime from the embedded bytes
    `(def ~name
       {:name ~(str name)
        :stage ~stage
        :spirv (let [arr# (byte-array ~spirv-vec)
                     buf# (ByteBuffer/allocateDirect (alength arr#))]
                 (.order buf# (ByteOrder/nativeOrder))
                 (.put buf# arr#)
                 (.flip buf#)
                 buf#)
        :source-hash ~hash})))

(defn load-spirv
  "Loads pre-compiled SPIR-V from a file, resource, or URL.

  Use this for shaders compiled outside of the defshader macro,
  such as shaders from external tools or third-party libraries.

  Parameters:
    source - File, URL, Path, or resource path string pointing to .spv file
    stage  - Shader stage keyword

  Returns:
    Map with :spirv ByteBuffer, :stage, :name (same shape as defshader output)"
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
      {:name (str source)
       :stage stage
       :spirv buf})))
