# Mercan — Android uygulaması (v0.4.0)

MercanAI/Mercan-0.8B-SFT (Türkçe, Q4_K_M) modelini tamamen telefonda çalıştıran sohbet uygulaması.
Android 8.0+ (arm64-v8a). **Derlenmiş çıktı:** `dist/Mercan-0.4.0.apk`.

## Özellikler

* Tasarım: Bookman Old Style (lisanslı dosyalar `fonts/`; Regular dosyasında ş/ğ/ı glifleri olmadığından o ağırlıkta
  geçici olarak TeX Gyre Bonum), renkler #AF372E / #333333 / #F6E6E3, logo `brand/mercan-01.svg` (uygulama ikonu)
  ve `mercan-04.svg` (yatay logo). Karanlık tema (DayNight).
* İlk açılışta 3 sayfalık tanıtım ("bu bir demodur", internetsiz çalışır, derin düşünme, Ar-Ge notu).
* Düşünme modu (ampul): asistan `Düşünce:` ile ön-doldurulur, `Cevap:` ile ayrılır, düşünce katlanır kart.
* Daktilo efekti, sohbet geçmişi çekmecesi (arama, Bugün/Dün/7 gün grupları, yeniden adlandır/sil, KV önbelleğine geri yükleme),
  ayarlar (sistem promptu, üretim parametreleri, iş parçacığı, model içe aktarma).
* Model güvenliği: model APK içinde değildir; ilk açılışta indirilir ve Android Keystore anahtarıyla şifreli saklanır
  (`model.mercan.enc`, AES-256-CTR + SHA-256); yüklerken geçici çözülür, mmap sonrası unlink. R8 açık.
* CPU çekirdekleri `GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS`: armv8.0 → armv8.2 dotprod → fp16 → **armv8.6 i8mm** → armv9 SVE2 → SME; açılışta en iyisi seçilir.

## Yerleşim

```
CMakeLists.txt        llama.cpp + libmercan + MercanChat motoru + JNI (MERCAN_BUILD_JNI=ON)
common/               MercanChat sohbet motoru (Windows uygulamasıyla ortak)
android/              Gradle projesi (Java UI, app/src/main/cpp/mercan_jni.cpp JNI köprüsü)
brand/                Mercan SVG logoları
fonts/                Bookman Old Style (ticari lisans kullanıcıya ait)
runtime/              Ahmet2001/mercanApp-test1 + yamalı llama.cpp için Mercan yamaları ve kurulum betiği
scripts/              build-android.sh, emülatör yardımcıları, gen_drawables.py
dist/                 Mercan-0.4.0.apk
```

## Derleme (WSL Ubuntu 24.04; Android SDK/NDK 27 `/opt/android-sdk`, Gradle 8.11, Rust aarch64-linux-android)

```bash
bash runtime/setup-runtime.sh        # ../repo: mercanApp-test1 + llama.cpp e71b805 (yamalı)
bash scripts/build-android.sh        # → out/android/Mercan-<sürüm>.apk
```

İmzalama ve özel model ucu: `android/keystore.properties` (depoya **eklenmez**) — `storeFile/storePassword/keyAlias/keyPassword`,
`modelUrl`, `modelToken`; yoksa debug imzası ve herkese açık HF adresi kullanılır. Model dosyası bu depoda yoktur.
