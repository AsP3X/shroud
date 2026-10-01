# Vendored: whisper.cpp v1.9.4

On-device Whisper for voice-message transcription (W2-WHISPER, media-voice-links §9.9, P7).

| | |
| --- | --- |
| Upstream | https://github.com/ggml-org/whisper.cpp |
| Tag | `v1.9.4` (released 2026-09-11; annotated tag object `7d75b14994ae7f59623e2471445e2355fe506ed2`) |
| Commit | `927cfce34f31707e17f2bff35c349632fb9e2c3a` |
| Source archive | `https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz`, SHA-256 `57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae` |
| Licence | MIT (`whisper.cpp/LICENSE`, authors in `whisper.cpp/AUTHORS`); ggml is part of the same repository under the same licence |
| Local changes | **none** — every file under `whisper.cpp/` is byte-identical to the archive |

## What is kept

Only what the Android build compiles, copied unchanged from the archive:

- `LICENSE`, `AUTHORS`
- `include/whisper.h`
- `src/whisper.cpp`, `src/whisper-arch.h`
- `ggml/CMakeLists.txt`, `ggml/cmake/`, `ggml/include/`
- `ggml/src/*` (top-level files) and `ggml/src/ggml-cpu/` (the CPU backend, all architectures)

Left out: the GPU and accelerator backends (`ggml/src/ggml-{blas,cann,cuda,et,hexagon,hip,metal,musa,opencl,openvino,rpc,sycl,virtgpu,vulkan,webgpu,zdnn,zendnn}`,
all off in `CMakeLists.txt`), whisper's own CMake files, Core ML / OpenVINO / Vitis AI glue, Parakeet,
bindings, examples, tests, samples, models and scripts.

## Checking the copy

```bash
curl -sSLo /tmp/w.tar.gz https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
shasum -a 256 /tmp/w.tar.gz      # 57e280ce…44ae
tar -xzf /tmp/w.tar.gz -C /tmp
cd android/app/src/main/cpp/whisper.cpp
find . -type f | while read -r f; do cmp -s "$f" "/tmp/whisper.cpp-1.9.4/$f" || echo "differs: $f"; done
```

## Updating

Replace the kept paths from the new tag's archive, update the table above, the commit in
`CMakeLists.txt` (`SHROUD_WHISPER_COMMIT`, the git stand-in) and `WHISPER_VERSION`, then re-run the
benchmark (`TranscriptionBenchmarkDeviceTest`). The CPU variant list comes from ggml's CMake and needs no
edit unless ggml renames its backends.
