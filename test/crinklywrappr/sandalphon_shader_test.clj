(ns crinklywrappr.sandalphon-shader-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as sg]
            [crinklywrappr.sandalphon.shader :as shader])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.io File]))

;; ============================================================================
;; Test Fixtures and Helpers
;; ============================================================================

(def ^:private simple-compute-shader
  "#version 450
layout(local_size_x = 1) in;
void main() {}")

(def ^:private simple-vertex-shader
  "#version 450
layout(location = 0) in vec3 position;
void main() {
  gl_Position = vec4(position, 1.0);
}")

(def ^:private simple-fragment-shader
  "#version 450
layout(location = 0) out vec4 outColor;
void main() {
  outColor = vec4(1.0, 0.0, 0.0, 1.0);
}")

(def ^:private invalid-glsl
  "#version 450
void main() {
  this is not valid glsl syntax
}")

(defn- create-temp-file [content suffix]
  (let [f (File/createTempFile "shader-test" suffix)]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- create-temp-spirv-file
  "Creates a temp file with valid SPIR-V magic number and minimal content."
  []
  (let [f (File/createTempFile "shader-test" ".spv")
        ;; Compile a real shader to get valid SPIR-V
        spirv (shader/compile-glsl simple-compute-shader :compute)]
    (.deleteOnExit f)
    (with-open [out (io/output-stream f)]
      (let [arr (byte-array (.remaining spirv))]
        (.get (.duplicate spirv) arr)
        (.write out arr)))
    f))

(defn- create-invalid-spirv-file
  "Creates a temp file with invalid SPIR-V (wrong magic number)."
  []
  (let [f (File/createTempFile "shader-test" ".spv")]
    (.deleteOnExit f)
    (spit f "not spirv content")
    f))

;; ============================================================================
;; Discovery Function Tests
;; ============================================================================

(deftest shader-stages-test
  (testing "shader-stages returns expected stages"
    (let [stages (shader/shader-stages)]
      (is (set? stages) "Returns a set")
      (is (pos? (count stages)) "Set is non-empty")
      (is (contains? stages :compute) "Contains :compute")
      (is (contains? stages :vertex) "Contains :vertex")
      (is (contains? stages :fragment) "Contains :fragment")
      (is (contains? stages :geometry) "Contains :geometry")
      (is (contains? stages :tess-control) "Contains :tess-control")
      (is (contains? stages :tess-evaluation) "Contains :tess-evaluation"))))

(deftest optimization-levels-test
  (testing "optimization-levels returns expected levels"
    (let [levels (shader/optimization-levels)]
      (is (set? levels) "Returns a set")
      (is (pos? (count levels)) "Set is non-empty")
      (is (contains? levels :zero) "Contains :zero")
      (is (contains? levels :size) "Contains :size")
      (is (contains? levels :performance) "Contains :performance"))))

(deftest target-environments-test
  (testing "target-environments returns expected targets"
    (let [targets (shader/target-environments)]
      (is (set? targets) "Returns a set")
      (is (pos? (count targets)) "Set is non-empty")
      ;; Check for at least one vulkan version
      (is (some #(sg/starts-with? (name %) "vulkan-") targets)
          "Contains at least one vulkan target"))))

;; ============================================================================
;; Compilable Protocol Tests
;; ============================================================================

(deftest compilable-string-inline-test
  (testing "String with newlines treated as inline source"
    (let [source "line1\nline2"]
      (is (= source (shader/->glsl source))))))

(deftest compilable-string-single-line-test
  (testing "Single-line string that's not a file treated as inline"
    (let [source "void main() {}"]
      (is (= source (shader/->glsl source))))))

(deftest compilable-file-exists-test
  (testing "File that exists returns its content"
    (let [content "#version 450\nvoid main() {}"
          f (create-temp-file content ".glsl")]
      (is (= content (shader/->glsl f))))))

(deftest compilable-file-not-exists-test
  (testing "File that doesn't exist throws"
    (let [f (io/file "/nonexistent/path/shader.glsl")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Shader file not found"
                            (shader/->glsl f))))))

(deftest compilable-path-test
  (testing "Path delegates to File implementation"
    (let [content "#version 450\nvoid main() {}"
          f (create-temp-file content ".glsl")
          p (.toPath f)]
      (is (= content (shader/->glsl p))))))

(deftest compilable-string-as-file-path-test
  (testing "String that matches a file path reads the file"
    (let [content "#version 450\nvoid main() {}"
          f (create-temp-file content ".glsl")]
      (is (= content (shader/->glsl (.getAbsolutePath f)))))))

;; ============================================================================
;; compile-glsl Tests
;; ============================================================================

(deftest compile-glsl-compute-test
  (testing "Compiles compute shader successfully"
    (let [spirv (shader/compile-glsl simple-compute-shader :compute)]
      (is (instance? ByteBuffer spirv))
      (is (pos? (.remaining spirv)))
      (is (= (ByteOrder/nativeOrder) (.order spirv))))))

(deftest compile-glsl-vertex-test
  (testing "Compiles vertex shader successfully"
    (let [spirv (shader/compile-glsl simple-vertex-shader :vertex)]
      (is (instance? ByteBuffer spirv))
      (is (pos? (.remaining spirv))))))

(deftest compile-glsl-fragment-test
  (testing "Compiles fragment shader successfully"
    (let [spirv (shader/compile-glsl simple-fragment-shader :fragment)]
      (is (instance? ByteBuffer spirv))
      (is (pos? (.remaining spirv))))))

(deftest compile-glsl-spirv-magic-number-test
  (testing "Compiled SPIR-V has correct magic number"
    (let [spirv (shader/compile-glsl simple-compute-shader :compute)
          ;; SPIR-V magic number is 0x07230203 in little-endian
          magic (bit-or (bit-and (.get spirv 0) 0xFF)
                        (bit-shift-left (bit-and (.get spirv 1) 0xFF) 8)
                        (bit-shift-left (bit-and (.get spirv 2) 0xFF) 16)
                        (bit-shift-left (bit-and (.get spirv 3) 0xFF) 24))]
      (is (= 0x07230203 magic)))))

(deftest compile-glsl-optimization-levels-test
  (testing "Different optimization levels compile successfully"
    (doseq [level (shader/optimization-levels)]
      (testing (str "Optimization level: " level)
        (let [spirv (shader/compile-glsl simple-compute-shader :compute
                                         :optimize level)]
          (is (instance? ByteBuffer spirv))
          (is (pos? (.remaining spirv))))))))

(deftest compile-glsl-target-environments-test
  (testing "Different target environments compile successfully"
    (doseq [target (shader/target-environments)]
      (testing (str "Target environment: " target)
        (let [spirv (shader/compile-glsl simple-compute-shader :compute
                                         :target-env target)]
          (is (instance? ByteBuffer spirv))
          (is (pos? (.remaining spirv))))))))

(deftest compile-glsl-invalid-stage-test
  (testing "Invalid stage throws with helpful error"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (shader/compile-glsl simple-compute-shader :invalid-stage)))]
      (is (= :invalid-stage (:stage (ex-data ex))))
      (is (set? (:valid-stages (ex-data ex)))))))

(deftest compile-glsl-invalid-optimize-test
  (testing "Invalid optimization level throws with helpful error"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (shader/compile-glsl simple-compute-shader :compute
                                               :optimize :invalid-opt)))]
      (is (= :invalid-opt (:optimize (ex-data ex))))
      (is (set? (:valid-levels (ex-data ex)))))))

(deftest compile-glsl-invalid-target-env-test
  (testing "Invalid target environment throws with helpful error"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (shader/compile-glsl simple-compute-shader :compute
                                               :target-env :invalid-target)))]
      (is (= :invalid-target (:target-env (ex-data ex))))
      (is (set? (:valid-targets (ex-data ex)))))))

(deftest compile-glsl-syntax-error-test
  (testing "GLSL syntax error throws with compiler output"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (shader/compile-glsl invalid-glsl :compute)))]
      (is (string? (:error (ex-data ex))))
      (is (pos? (:num-errors (ex-data ex)))))))

(deftest compile-glsl-from-file-test
  (testing "Compiles shader from file"
    (let [f (create-temp-file simple-compute-shader ".glsl")
          spirv (shader/compile-glsl f :compute)]
      (is (instance? ByteBuffer spirv))
      (is (pos? (.remaining spirv))))))

;; ============================================================================
;; defshader Macro Tests
;; ============================================================================

(shader/defshader test-compute-shader
  :stage :compute
  :source "#version 450
layout(local_size_x = 64) in;
void main() {}")

(deftest defshader-creates-var-test
  (testing "defshader creates var with expected structure"
    (is (map? test-compute-shader))
    (is (contains? test-compute-shader :name))
    (is (contains? test-compute-shader :stage))
    (is (contains? test-compute-shader :spirv))
    (is (contains? test-compute-shader :source-hash))))

(deftest defshader-name-test
  (testing "defshader :name is the symbol name"
    (is (= "test-compute-shader" (:name test-compute-shader)))))

(deftest defshader-stage-test
  (testing "defshader :stage matches input"
    (is (= :compute (:stage test-compute-shader)))))

(deftest defshader-spirv-test
  (testing "defshader :spirv is a ByteBuffer"
    (is (instance? ByteBuffer (:spirv test-compute-shader)))
    (is (pos? (.remaining (:spirv test-compute-shader))))
    (is (= (ByteOrder/nativeOrder) (.order (:spirv test-compute-shader))))))

(deftest defshader-source-hash-test
  (testing "defshader :source-hash is a hex string"
    (is (string? (:source-hash test-compute-shader)))
    (is (= 64 (count (:source-hash test-compute-shader)))) ; SHA-256 = 64 hex chars
    (is (re-matches #"[0-9a-f]+" (:source-hash test-compute-shader)))))

(shader/defshader test-optimized-shader
  :stage :compute
  :source "#version 450
layout(local_size_x = 64) in;
void main() {}"
  :optimize :performance)

(deftest defshader-with-optimization-test
  (testing "defshader with optimization level"
    (is (instance? ByteBuffer (:spirv test-optimized-shader)))))

;; ============================================================================
;; load-spirv Tests
;; ============================================================================

(deftest load-spirv-valid-file-test
  (testing "load-spirv loads valid SPIR-V file"
    (let [f (create-temp-spirv-file)
          result (shader/load-spirv f :compute)]
      (is (map? result))
      (is (contains? result :name))
      (is (contains? result :stage))
      (is (contains? result :spirv))
      (is (= :compute (:stage result)))
      (is (instance? ByteBuffer (:spirv result))))))

(deftest load-spirv-from-path-test
  (testing "load-spirv loads from Path"
    (let [f (create-temp-spirv-file)
          result (shader/load-spirv (.toPath f) :compute)]
      (is (instance? ByteBuffer (:spirv result))))))

(deftest load-spirv-from-string-path-test
  (testing "load-spirv loads from string path"
    (let [f (create-temp-spirv-file)
          result (shader/load-spirv (.getAbsolutePath f) :compute)]
      (is (instance? ByteBuffer (:spirv result))))))

(deftest load-spirv-invalid-magic-test
  (testing "load-spirv throws on invalid magic number"
    (let [f (create-invalid-spirv-file)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid SPIR-V: bad magic number"
                            (shader/load-spirv f :compute))))))

(deftest load-spirv-too-small-test
  (testing "load-spirv throws on file too small"
    (let [f (File/createTempFile "tiny" ".spv")]
      (.deleteOnExit f)
      (spit f "abc") ; Only 3 bytes, need at least 4
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid SPIR-V: bad magic number"
                            (shader/load-spirv f :compute))))))

;; ============================================================================
;; Source Hash Consistency Tests
;; ============================================================================

(shader/defshader hash-test-shader-1
  :stage :compute
  :source "#version 450\nlayout(local_size_x = 1) in;\nvoid main() {}")

(shader/defshader hash-test-shader-2
  :stage :compute
  :source "#version 450\nlayout(local_size_x = 1) in;\nvoid main() {}")

(shader/defshader hash-test-shader-different
  :stage :compute
  :source "#version 450\nlayout(local_size_x = 2) in;\nvoid main() {}")

(deftest source-hash-consistency-test
  (testing "Same source produces same hash"
    (is (= (:source-hash hash-test-shader-1)
           (:source-hash hash-test-shader-2)))))

(deftest source-hash-different-test
  (testing "Different source produces different hash"
    (is (not= (:source-hash hash-test-shader-1)
              (:source-hash hash-test-shader-different)))))
