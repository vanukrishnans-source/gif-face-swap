# GIF Face Swap 1.0.1

Offline Android app that swaps a face from a photo onto every frame of an animated GIF.

### 1.0.1

- **Bugfix:** exported GIFs showed a broken thumbnail in Gallery / Photos / file managers. Root cause: GIF LZW encoder emitted the Clear code at the *reset* code width instead of the *current* width once the dictionary filled, producing invalid GIF89a streams. Also writes a Global Color Table (gallery-friendly) and refuses to publish empty/invalid files into MediaStore.

- **Package:** `com.vanu.giffaceswap`
- **Version:** 1.0.1 (versionCode 2)
- **Min SDK:** 24 · **Target SDK:** 35
- **New standalone app** (separate from Couple Face Swap / Face Swap Video)

## Download

| APK | Size | SHA-256 |
|---|---|---|
| [GifFaceSwap-1.0.1-arm64.apk](https://github.com/vanukrishnans-source/gif-face-swap/releases/download/v1.0.1/GifFaceSwap-1.0.1-arm64.apk) (use this) | 17,137,267 B (~16.3 MB) | `9dbc60e092ab9528898f26d6a7d91a17e6d80f987c7c18c94719f2ab50c5d7bf` |
| [GifFaceSwap-1.0.1-armeabi-v7a.apk](https://github.com/vanukrishnans-source/gif-face-swap/releases/download/v1.0.1/GifFaceSwap-1.0.1-armeabi-v7a.apk) | 15,591,275 B (~14.9 MB) | `e6d96de504f2015eb83c25283506fa1e8a7b23c2f24d23d9fe6d0e1bf30b3d02` |

arm64 covers current phones (Galaxy S24 Ultra, ROG Phone 3, etc.). Previous: [v1.0](https://github.com/vanukrishnans-source/gif-face-swap/releases/tag/v1.0).

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
