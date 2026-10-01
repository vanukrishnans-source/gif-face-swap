# GIF Face Swap 1.0

Offline Android app that swaps a face from a photo onto every frame of an animated GIF.

- **Package:** `com.vanu.giffaceswap`
- **Version:** 1.0 (versionCode 1)
- **Min SDK:** 24 · **Target SDK:** 35
- **New standalone app** (separate from Couple Face Swap / Face Swap Video)

## Download

| APK | Size | SHA-256 |
|---|---|---|
| [GifFaceSwap-1.0-arm64.apk](https://github.com/vanukrishnans-source/gif-face-swap/releases/download/v1.0/GifFaceSwap-1.0-arm64.apk) (use this) | 17,135,991 B (~16.3 MB) | `aa47ee61fb4755a96b7ac890eec0400518f0e60022217e9c34a9bb65d0029ad0` |
| [GifFaceSwap-1.0-armeabi-v7a.apk](https://github.com/vanukrishnans-source/gif-face-swap/releases/download/v1.0/GifFaceSwap-1.0-armeabi-v7a.apk) | 15,589,999 B (~14.9 MB) | `93c6c6af0815852ac1aee7474d33961430d94f3d39d0b9ac607d34dc1f0a1466` |

arm64 covers current phones (Galaxy S24 Ultra, ROG Phone 3, etc.).

## How to use

1. Install the APK (allow install from this source if prompted).
2. First launch: download the AI models (~452 MB — ArcFace + inswapper). One-time; resumable; GitHub → Hugging Face mirror.
3. Tap **GIF** → pick an animated GIF.
4. Tap **Face from** → pick a clear face photo.
5. Optional: **Enhance** Off / Light (76 MB extra) / HQ (284 MB extra).
6. Tap **Create face swap GIF**. Progress stays in a notification if you leave the app.
7. **Save** to `Pictures/GifFaceSwap/` or **Share**. **Flip** re-pairs left/right when both sides have 2+ faces.

## Limits

| Limit | Value |
|---|---|
| Max frames processed | 80 (evenly subsampled if longer) |
| Max duration used | 12 seconds |
| Max short side while processing | 480 px |
| Max GIF file size | 25 MB |
| Output | Animated GIF (GIF89a) |

Frames with no detectable face are left unchanged. Everything runs on-device after the model download — GIFs and photos never leave the phone.

## Models (same as Face Swap Video)

Downloaded into the app’s private `filesDir/models` with SHA-256 checks:

| file | bytes |
|---|---|
| arcface_w600k_r50.onnx | 174,388,474 |
| inswapper_128_fp16.onnx | 277,680,829 |
| gpen_bfr_256.onnx (optional Light) | 75,792,988 |
| gpen_bfr_512.onnx (optional HQ) | 284,340,240 |

InsightFace models are for non-commercial research unless you have a commercial licence from insightface.ai.

## Build

```bash
export JAVA_HOME=… ANDROID_HOME=…
cp keystore.properties.example keystore.properties   # edit passwords
# place release.jks next to keystore.properties (same keystore as other vanu apps)
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a}-release.apk
```

Modules: `:core` (shared ONNX / MediaPipe / Compose UI) · `:app` (this GIF UI + GIF decode/encode + job service).

## Licence note

App code in this repo: yours. Model weights: InsightFace / FaceFusion asset licences (non-commercial research unless licensed).
