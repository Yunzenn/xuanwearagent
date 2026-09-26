# Contributing

This project is a wrist-worn companion agent for one specific piece of hardware. It is not a generic
Android app, and several things that are normal elsewhere are deliberately excluded here. Please read
this before opening a PR — most rejected changes are rejected because they cross one of the boundaries
below, not because the code is bad.

## Read these first, in this order

| File | Why |
|---|---|
| [`PRODUCT_REQUIREMENTS.md`](PRODUCT_REQUIREMENTS.md) | **The project memory.** What we are building, the frozen boundaries, the gates. |
| [`ROADMAP.md`](ROADMAP.md) | Current version line (v0.1 → v1.0), what each version must demonstrate. |
| [`README.md`](README.md) | Current state, architecture, build, known risks. |
| [`REUSE_AUDIT.md`](REUSE_AUDIT.md) | Licence status per reference project. Check it before proposing a dependency. |

## Boundaries that are not up for debate

```text
No Compose, no Wear Compose, no Wear OS runtime, no new UI stack.
Native Android Views only - CD12Max is Full Android, compatibility first.

No on-device ASR/LLM/TTS. The watch is a thin client; the server does the heavy work.
  (Unisoc W527, 12 nm, 1x A75 + 3x A55, 1400 mAh.)

No shell for the model, ever. Tools are typed. `exec_shell("anything")` is not a design option.

Never commit third_party/live2d. It is a private local dependency.
  git ls-files third_party/live2d   must always be empty.

Never commit customer or licensed character assets, and never a named voice actor's voiceprint.
```

## Target hardware

```text
CD12Max 4+32G, Full Android 9 / API 28
2.06" AMOLED, 410 x 502 px, ~315 dpi -> Android's 320 bucket (density 2.0)
Usable canvas: 205 x 251 dp   (NOT 410 x 502 dp - this mistake already cost one rework)
```

Any visual judgement must be made after overriding the emulator to the same geometry:

```bash
adb shell wm size 410x502
adb shell wm density 320
adb shell dumpsys window displays | grep 'base=410x502'   # confirm it took effect
```

## Build

The Gradle wrapper is committed, so use `./gradlew`. The offline flags below are what this project's dev
machine uses; with network access the normal Gradle resolution works too.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

### Known limitation: a fresh clone cannot build `:app`

`:app` depends on `:core-live2d`, which compiles against the **official Live2D Cubism SDK that we may not
redistribute**. That SDK is gitignored. Until the dependency is made conditional, a clean checkout can
only build and test the pure-Kotlin modules:

```bash
./gradlew :core-protocol:test :core-audio:test
```

If you are contributing to the protocol, audio or memory layers, that is enough. If your change needs
`:app`, say so in the PR and a maintainer will build it.

## Tests

**The UTP runner does not start in this environment.** Do not report a change as verified because Gradle's
connected test task "passed" — it does not run. Use `adb` directly:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
    -e class 'com.aiwatch.probe.CompanionHomeTest#pushToTalkDrivesTheFourStatesAndAppendsBothSides' \
    com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner
```

**Run one test method per `am instrument` invocation.** Running a whole class in one process lets static
state from one test contaminate the next, which produces failures that are not real.

## Evidence rules

This is the part that matters most, and the easiest to erode.

```text
A claim you have not verified must be written as PENDING or UNVERIFIED. Never as PASS.

Static evidence is not runtime evidence.
  "the patch applies cleanly / tests pass in isolation"  !=  "it works on the device"

Simulator results are not device results.
  The emulator cannot tell you about ABI, GPU texture limits, microphone, speaker,
  background survival, battery, or vendor-ROM behaviour.

Distinguish a positive control from a real result.
  A harness passing because some other component produced pixels is not evidence
  that the component under test produced pixels.
```

If your PR says something works, the PR must say how you know, with the command and the output.

## Branches and commits

Trunk-based. There is exactly one long-lived branch, `main`, and it must stay explainable and verifiable.
There is no `develop`.

```text
feat/<issue>-<name>       fix/<issue>-<name>        test/<issue>-<name>
refactor/<issue>-<name>   docs/<issue>-<name>       chore/<issue>-<name>
```

Examples: `test/31-voice-contract-harness`, `feat/42-memory-gateway`.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/). `main` is squash-merged, so
your branch history does not need to be tidy — the commit that lands does.

```text
refactor(voice): add injectable boundaries for P0-2B contract testing
test(voice): add P0-2B XiaozhiVoiceSession contract harness
fix(voice): reset per-turn playback latency state
```

Keep commits single-purpose. A testability refactor and the tests that use it are two commits, so a
bisect can tell them apart.

## Pull requests

`main` requires a pull request; direct pushes are not accepted. Fill in the PR template — in particular
the **Verification** and **Evidence status** sections. A PR that changes behaviour without saying how it
was verified will be asked for evidence before review.

## Licence

Project code is [Apache-2.0](LICENSE). That covers **this repository's code only**. It does not cover:

```text
the Live2D Cubism SDK                    (proprietary, not in this repository)
character / model assets                 (customer-supplied, not redistributable)
bundled third-party models               (each under its own terms)
concentus / Opus                         (see app/src/main/assets/licenses/)
```

Before adding a dependency or copying code, check its licence and record it in
[`REUSE_AUDIT.md`](REUSE_AUDIT.md) with a status of `DIRECT`, `ADAPT` or `REFERENCE ONLY`. Unverified
licences must be written as unverified.

## Commit identity

Author identity is deliberately pseudonymous and pinned. See [`GIT_PRIVACY.md`](GIT_PRIVACY.md). Do not
add personal information to commits.
