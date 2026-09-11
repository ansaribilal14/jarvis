# Device Compatibility

- **Minimum**: Android 10 (API 29), arm64-v8a, 3 GB RAM recommended.
- **APK**: arm64-v8a only (primary production target per project spec; armeabi-v7a has no
  meaningful benefit for LLM workloads).
- **Inference**: CPU (4-thread default). Vulkan/OpenCL GPU offload intentionally disabled
  in v1 for build + behavior stability; roadmap item.
- **Class ladder**: BASIC (0.5B model) / STANDARD (1.5B-1.7B) / POWER & HIGH-END (3B).
- **Voice**: on-device STT depends on the device's speech provider (Google app usually);
  TTS via default engine. Both degrade with honest messages rather than failing silently.
- **Screenshot/OCR**: API 30+ for the screenshot path; below that the accessibility tree
  remains fully functional.

Honest scope: "every supported device has a sensible local-AI path" - not "every model
runs on every phone". The Models tab refuses recommendations the device cannot run.
