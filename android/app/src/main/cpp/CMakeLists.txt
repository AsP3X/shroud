# On-device Whisper for Shroud (W2-WHISPER; media-voice-links §9.9, 00-plan §5.2, P7).
#
# Builds the vendored whisper.cpp v1.9.4 (see VENDORED.md) for arm64-v8a and x86_64 into:
#   libshroud_whisper.so      the JNI bridge (whisper_jni.cpp) with whisper.cpp compiled in, symbols hidden
#   libggml.so, libggml-base.so  ggml's registry and core
#   libggml-cpu-*.so          the CPU backend, built per instruction-set level and picked at run time
#
# arm64: every ggml Android variant (armv8.0 … armv9.2 with SME). A phone without dot product
# (Cortex-A53/A73 phones still run Android 11) gets the armv8.0 build instead of a SIGILL; a newer
# one gets dotprod/fp16/i8mm kernels. whisper_jni.cpp scores the variants the way ggml's own
# loader does and loads the best one before the first model (media §9.9: "runtime-dispatched CPU
# variants if the tag offers GGML_CPU_ALL_VARIANTS" — v1.9.4 does, through GGML_BACKEND_DL).
# x86_64 (emulators, Chromebooks): one CPU backend at the ABI's guaranteed level, SSE4.2.
#
# Only this file and the Gradle `region native` configure the build; nothing here downloads.
cmake_minimum_required(VERSION 3.22)
project(shroud_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Whisper at -O0 is far too slow to use, and the instrumented benchmark runs on the debug APK:
# every build type compiles like a release (-O3 -DNDEBUG), so debug and release ship the same
# native code (00-plan §5.2: -O3).
set(CMAKE_BUILD_TYPE Release CACHE STRING "Native code is always optimized (W2-WHISPER)" FORCE)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Reproducible builds (00-plan §5.2, W4 F-Droid check): ggml's asserts embed __FILE__, so the
# checkout's absolute path would end up in the libraries. Map it to a fixed prefix.
add_compile_options(
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=shroud-cpp"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=shroud-cpp-build"
)

# ggml asks git for its commit. Inside the Shroud repository that would be our HEAD plus a dirty
# flag, varying from checkout to checkout; a stand-in answers with the vendored tag's commit.
set(SHROUD_WHISPER_COMMIT 927cfce34f31707e17f2bff35c349632fb9e2c3a)
set(SHROUD_NO_GIT ${CMAKE_BINARY_DIR}/shroud-vendored-git)
file(WRITE ${SHROUD_NO_GIT}
    "#!/bin/sh\n# Stand-in for git inside the vendored ggml tree (shroud CMakeLists.txt).\n"
    "if [ \"$1\" = rev-parse ]; then echo 927cfce; fi\nexit 0\n")
file(CHMOD ${SHROUD_NO_GIT} PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
set(GIT_EXE ${SHROUD_NO_GIT} CACHE FILEPATH "Stand-in git for the vendored ggml (reproducible GGML_COMMIT)" FORCE)

# ggml configuration. Everything that would download, probe the build machine or pull in a
# runtime the app does not ship stays off.
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)   # GGML_BACKEND_DL needs shared libraries
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)   # CPU variants as separate libraries
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)   # never tune for the build machine (00-plan §5.2)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)   # ggml's own thread pool; no libomp to ship
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)   # hermetic builds
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)   # would fetch sources at configure time
set(GGML_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)   # third-party code: keep the build log readable
set(GGML_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
foreach(backend BLAS CUDA HIP METAL MUSA RPC SYCL VULKAN WEBGPU OPENCL CANN ZDNN ZENDNN HEXAGON OPENVINO VIRTGPU ET)
    set(GGML_${backend} OFF CACHE BOOL "" FORCE)
endforeach()

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    # The Android x86_64 ABI guarantees SSE4.2 and POPCNT (NDK ABI guide); nothing above it.
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_SSE42 ON  CACHE BOOL "" FORCE)
    foreach(feature AVX AVX2 AVX_VNNI BMI2 FMA F16C AVX512 AVX512_VBMI AVX512_VNNI AVX512_BF16 AMX_TILE AMX_INT8 AMX_BF16)
        set(GGML_${feature} OFF CACHE BOOL "" FORCE)
    endforeach()
endif()

add_subdirectory(${WHISPER_DIR}/ggml ggml)

# The CPU backend libraries ggml just declared ("ggml-cpu-android_armv8.0_1;…" or "ggml-cpu"),
# handed to the bridge so it can score and load them by name from the APK.
set(SHROUD_CPU_BACKENDS "")
foreach(backend IN LISTS GGML_AVAILABLE_BACKENDS)
    if (backend MATCHES "^ggml-cpu")
        list(APPEND SHROUD_CPU_BACKENDS ${backend})
    endif()
endforeach()
if (NOT SHROUD_CPU_BACKENDS)
    message(FATAL_ERROR "ggml declared no CPU backend")
endif()
string(REPLACE ";" "," SHROUD_CPU_BACKENDS_CSV "${SHROUD_CPU_BACKENDS}")
message(STATUS "Shroud whisper: CPU backends ${SHROUD_CPU_BACKENDS_CSV}")

add_library(shroud_whisper SHARED
    whisper_jni.cpp
    ${WHISPER_DIR}/src/whisper.cpp
)
target_include_directories(shroud_whisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
target_compile_definitions(shroud_whisper PRIVATE
    WHISPER_VERSION="1.9.4"
    SHROUD_WHISPER_COMMIT="${SHROUD_WHISPER_COMMIT}"
    SHROUD_CPU_BACKENDS="${SHROUD_CPU_BACKENDS_CSV}"
)
# Only the JNI entry points are exported; whisper.cpp's own API stays internal to the library.
target_compile_options(shroud_whisper PRIVATE
    -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
target_link_options(shroud_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(shroud_whisper PRIVATE ggml ggml-base log dl)
