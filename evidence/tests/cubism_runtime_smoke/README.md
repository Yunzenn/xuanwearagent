# Cubism runtime smoke harness — mirrored copy

This directory is a **read-only mirror** of the working verification project. The working copy lives at
`third_party/live2d/sdk-r5/runtime-smoke/`, which is inside a Git-ignored path because that tree also
holds the proprietary official SDK. The mirror exists so the verification harness survives in version
control as an audit artefact; it is not the copy that Gradle builds.

## Module layout (integration shape)

```
runtime-smoke/                     root project, com.android.application  (the sample host)
  build.gradle
  settings.gradle                  includes ':cubism-framework'
  cubism-framework/build.gradle    com.android.library adapter for the official framework
  src/androidTest/...              RuntimeSmokeTest
```

The framework source is **not flattened into the app module**. `:cubism-framework` is a thin descriptor
that points `java.srcDirs` and `assets.srcDirs` at the official, unmodified
`CubismSdkForJava-5-r.5/Framework/framework/src/main`. The app depends on `project(':cubism-framework')`
and supplies `Live2DCubismCore.aar` at runtime, because the official module declares Core as
`compileOnly`.

### Why an adapter module rather than the SDK's own `Framework/framework/build.gradle`

Verified 2026-09-25: the official module descriptor cannot be configured in this environment.

1. It sets `compileSdk PROP_COMPILE_SDK_VERSION.toInteger()` = **36**, and only the **android-35**
   platform is installed. There is no android-36 and no network to fetch one.
2. It declares `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`, and the only JDK
   present is **21**. No toolchain download repository is configured, so Gradle fails with
   *"No locally installed toolchains match"*.

Both are environment gaps, not framework defects. The adapter keeps the official source authoritative
while satisfying the same architectural goal. `gradle.properties` in this project therefore defines
`PROP_COMPILE_SDK_VERSION=35` / `PROP_MIN_SDK_VERSION=21` / `PROP_TARGET_SDK_VERSION=35` locally rather
than editing the SDK.

**If android-36 and JDK 17 are provisioned, prefer deleting the adapter and including the official
module directly** (`project(':cubism-framework').projectDir = file('…/Framework/framework')`). That is
the shape the product should end up in; this adapter is the offline fallback.

### Asset trap

`CubismSdkForJava-5-r.5/Framework/framework/src/main/assets/com/live2d/sdk/cubism/framework/shaders/standardES/`
holds the GLSL shaders that `CubismShaderAndroid` loads **at runtime**. A library module that ships only
`java.srcDirs` compiles and packages cleanly and then fails on the device. The mirror's `build.gradle`
sets `assets.srcDirs` accordingly; the rebuilt APK contains all 36 shader files.

## What it verifies

`com.aiwatch.smoke.RuntimeSmokeTest#twoModelSmoke` is an instrumentation test that runs the **official
Cubism r.5 sample** against the local `Live2DCubismCore.aar` only. No business module (`app`,
`core-protocol`, `core-audio`) is involved, so a failure here cannot be confused with product code.

It deliberately does **not** accept "no GL error" as proof of rendering. It requires:

1. **Real pixels** — `PixelCopy` (API 24+) copies the presented surface into a bitmap; the frame must
   contain more than 8 distinct colours and more than 5000 non-background pixels.
2. **A live render** — consecutive frames are diffed, so a frozen or never-swapped buffer is visible.
3. **A live update pipeline** — every model parameter is sampled 16 times over ~2 s; at least one
   parameter must move, which is what proves motion / blink / physics / pose updaters are running.
4. **GL context recreation** — `setPreserveEGLContextOnPause(false)` then pause/resume, followed by a
   fresh pixel capture, so a restored-but-blank surface fails.

`featureAttribution` then attributes each framework subsystem by **A/B control**: it removes the target
updater from the framework's own `CubismUpdateScheduler.cubismUpdatableList` (private, reached by
reflection), re-measures, and restores it.

The criterion matters more than the mechanism, and getting it wrong cost two revisions:

- **Range-based attribution does not work here.** The sample restarts a *random* idle motion whenever the
  current one finishes, and `LAppModel.update()` calls `loadParameters()/saveParameters()` every frame, so
  state carries across arms. An A/A control (same configuration measured twice) shows 9-17 of 42
  parameters differing by more than 0.02. Restarting the same idle motion to remove the phase difference
  was tried and **rejected**: a fresh motion makes the framework report "motion updated", which suppresses
  the eye-blink updater, so the blink signal vanishes entirely.
- **The usable criterion is phase-independent: a parameter that is no longer written at all has a range of
  exactly zero**, which motion phase cannot fake. Blink keeps a range criterion only because its amplitude
  (1.0) is an order of magnitude above the noise floor.
- A first revision reported a **pose effect that did not reproduce** on the next run — the real cause was
  motion phase, not pose. It was withdrawn. Every conclusion here therefore requires the A/A control and
  reproduction across repeated runs.

Results: blink, breath, physics and expression are each attributed by measurement, and the expression
check matches the `.exp3.json` declared values exactly. **Pose is `N/A BY ASSET`**: every official sample
model ships `pose3.json` with empty `Link` arrays (Haru 4 parts, Hiyori 2, Mao 4, Natori 8), so pose is a
no-op by design for them. The pose assertion is self-justifying — it reads `linkedParameter` from the pose
object at runtime and asserts the observed opacity change is consistent with that declaration, so it
neither passes falsely on an inert pose nor fails falsely on a working one.

Captured PNGs are written to the app's external files dir and pulled to `.tools/smoke-captures/`.

## Running it

```powershell
# full run: build, install, run, capture, write evidence/reports/cubism-runtime-smoke.txt
D:\AIwatch\evidence\tests\run_cubism_smoke.ps1

# re-run against already-built APKs
D:\AIwatch\evidence\tests\run_cubism_smoke.ps1 -SkipBuild
```

## Three traps the script already handles

1. **`am instrument` instead of `connectedAndroidTest`.** Gradle's UTP runner aborts at startup with only

   ```
   信息: Constructing runner from config.
   严重: Fatal error while executing main with args: --proto_config=... --proto_server_config=...
   ```

   in `build/outputs/androidTest-results/connected/debug/<device>/utp.0.log` (267 bytes, no stack
   trace), before it installs anything. The script drives `adb install` + `am instrument` instead, which
   produces the same JUnit result and is sufficient for the runtime gate. This is a tooling gap in this
   environment, not evidence about the product.

2. **Explicit `:` project paths on the Gradle task names.** Bare `assembleDebugAndroidTest` also runs
   `:cubism-framework:assembleDebugAndroidTest`, whose signing step needs a debug keystore lock under
   the user profile — outside this workspace and therefore denied. Only the app module's APKs are
   wanted, so the script passes `:assembleDebug :assembleDebugAndroidTest`.

3. **One `am instrument` invocation per test method.** Running the whole class in a single process is
   flaky, because the official sample keeps the Activity in static singletons (`LAppPal`,
   `LAppDelegate`). Once the first test finishes its Activity the static reference can be stale, and the
   second test's GL thread then dies with

   ```
   NullPointerException: Attempt to invoke virtual method 'android.content.res.AssetManager
     android.app.Activity.getAssets()' on a null object reference
     at com.live2d.demo.full.LAppPal.loadFileAsBytes(LAppPal.java:55)
     at ...CubismShaderAndroid.getInstance(CubismShaderAndroid.java:56)
     at com.live2d.demo.full.LAppDelegate.onSurfaceCreated(LAppDelegate.java:92)
   ```

   Observed 2026-09-25: the identical whole-class order reported `OK (2 tests)` once and crashed the
   next run, so this is a race, not a deterministic ordering rule. Separate processes per method remove
   the shared state. **This also matters for the product**: the sample's static-singleton lifecycle is
   not safe to copy into an app that recreates Activities.

## Environment notes

- Writes to the user-profile `.gradle` directory are denied here. The run script sets `GRADLE_USER_HOME`
  inside the workspace (`.tools/gradle-home`, already ignored) and consumes the existing cache read-only
  through `GRADLE_RO_DEP_CACHE`, so no dependency re-download is needed.
- `adb` is not on `PATH`; it is `D:\AIwatch\.android-sdk\platform-tools\adb.exe`.
- Page size is **measured** (`/proc/self/smaps`), never assumed: it decides whether a 16 KB result is
  even relevant to the device under test.
