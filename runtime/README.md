# runtime/

Mercan çalışma zamanı bu depoda **değil**; `Ahmet2001/mercanApp-test1` (commit `2bd274f`) + onun hazırladığı
yamalı `llama.cpp e71b805` kullanılır. Bu klasördeki dosyalar o ağacın üzerine eklenen Mercan uygulama yamalarıdır:

| Dosya | Ne yapar |
|---|---|
| `mercanApp-test1-context-clear.patch` | libmercan'a `mercan_context_clear()` ekler (KV önbelleğini sıfırlama; sohbet motoru kullanır). |
| `llama-cpp-mingw-power-throttling.patch` | `ggml-cpu.c` için mingw-w64 uyumluluk şimi (`THREAD_POWER_THROTTLING_STATE`). |
| `patch_llama_cmake.py` | llama.cpp `src/CMakeLists.txt` üzerinde idempotent CMake düzeltmeleri (tokenizer FFI staticlib bağlama). |
| `setup-runtime.sh` | Hepsini uygular: klon → yamalar → `scripts/prepare_llama.sh` → `mercan_kv.h` kopyası. |

```bash
bash runtime/setup-runtime.sh            # ../repo altına kurar
```
