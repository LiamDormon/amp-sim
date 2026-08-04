# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AmpChain (package `org.ampsim`) is a GTK4/libadwaita desktop app for chaining
guitar amp/pedal DSP simulations, written in Kotlin and integrating with JACK
for real-time audio I/O. Root Gradle project name is `amp-sim`; the single
module is `app`.

`docs/AMPCHAIN_DESIGN_SPEC.md` is the aspirational product/architecture spec
(note: it uses an older package name `com.ampchain`/`Unit` model class, Kotest,
LV2 hosting — these are **not** what's implemented). `docs/AUDIO_ENGINE_AND_DSP.md`
describes the **actual current implementation** of the audio engine and DSP
pipeline under `app/src/main/kotlin/org/ampsim/{audio,dsp}` — read that one
first when touching audio code, it stays in sync with the code.

## Build, run, test

Requires JDK 25 and native tools on PATH: `blueprint-compiler`,
`glib-compile-resources`, plus GTK4/libadwaita/JACK dev headers at runtime
(see `.github/workflows/ci-build.yaml` for the exact apt packages on
Ubuntu/GNOME).

```bash
./gradlew build          # full build (compiles resources, Kotlin, runs tests)
./gradlew run             # launch the app
./gradlew test            # run all tests (JUnit 5 / kotlin-test)
./gradlew test --tests "org.ampsim.audio.AudioEngineTest"        # single test class
./gradlew test --tests "org.ampsim.audio.AudioEngineTest.someTestName"  # single test
```

UI resources go through a Gradle-wired pipeline before Kotlin compiles:
`gtk/mainwindow.blp` → (`compileBlueprint`, via `blueprint-compiler`) →
`mainwindow.ui` → (`compileResources`, via `glib-compile-resources`) →
`ampsim.gresource`, which `compileKotlin` depends on. If you edit
`mainwindow.blp` or `resources.xml`, a plain `./gradlew build` regenerates the
downstream files — don't hand-edit `mainwindow.ui` or `ampsim.gresource`.

Application entry point is `org.ampsim.AppKt` (`App.kt`); GTK/native access
requires `--enable-native-access=ALL-UNNAMED`, already wired into the `run`
and `test` JVM args in `app/build.gradle.kts`.

## Architecture

Three layers, each progressively more real-time-constrained:

- **Model** (`model/`) — plain, immutable `@Serializable` data classes
  (`Chain`, `EffectUnit`, `Preset`, `AppConfiguration`) describing a rig
  declaratively. No behavior beyond copy-based mutators (`addUnit`,
  `moveUnit`, `setParameter`, ...). This is the "what", not the "how".
- **DSP** (`dsp/`) — `DSPModule` interface + `BaseDSPModule` (shared parameter
  map, buffer validation, state (de)serialization) + concrete effects in
  `dsp/effects/` (`GenericOverdrive`, `GenericAmp`, `GenericDelay` — placeholder
  simulations, not modeled hardware). `DSPModuleFactory` turns model objects
  into runnable `DSPModule` instances **off the audio thread**.
- **Audio** (`audio/`) — `AudioEngine` owns the JACK client
  (`JackClient`, a thin JNAJack wrapper), the active DSP chain, and the
  real-time process callback. `LockFreeRingBuffer<T>` is a pre-allocated SPSC
  wait-free queue carrying `AudioCommand`s from the control thread to the
  audio thread; the reverse direction (metering) uses plain `@Volatile`
  fields. No locks anywhere on this path.

**The hard real-time rule**: the JACK callback thread (inside
`AudioEngine.process`) must never allocate and never block. New DSP modules
are always built on the control/UI thread and handed over as a whole via a
`LoadChain` command — the audio thread only ever swaps a pre-built list. See
`docs/AUDIO_ENGINE_AND_DSP.md` § "Real-Time Safety Rules" before touching
anything in `audio/` or adding a new DSP effect.

**Event-driven wiring**: `ChainManager` (`chain/ChainManager.kt`) is the sole
owner/mutator of the canonical `Chain` (as a `StateFlow`) and publishes every
mutation as a `UIEvent` on `EventBusImpl` (`events/`), a `Channel`-backed
multicast bus. `App.kt` subscribes to `chainModified`/`parameterChanged` and
marshals the resulting `AudioEngine` calls onto the GTK main thread via
`GLib.idleAdd` — this keeps `AudioEngine`'s command queue single-producer even
though the event bus itself fans out across coroutine dispatchers.
`ChainEditorModel` (`ui/chain/`) is a GTK-facing adapter over `ChainManager`
that *also* notifies local listeners synchronously on the calling thread, so
the `ChainEditor` canvas widget can safely touch GTK state directly from a
mutation's call stack.

**Structural vs. parameter changes are deliberately different paths**:
structural chain edits (add/remove/reorder/toggle) publish `ChainModified` and
trigger a full `LoadChain` rebuild; a single parameter tweak (e.g. dragging a
`Dial`) publishes only `ParameterChanged` and calls `AudioEngine.updateParameter`
in place, so a knob drag never resets another module's internal state (e.g. a
delay's buffer). Preserve this split when adding new mutation paths.

**Persistence**: `ConfigManager` (`persistence/`) loads/saves
`AppConfiguration` as JSON at `~/.config/amp-sim/config.json` via
kotlinx.serialization, serialized onto a single-threaded `Dispatchers.IO`
dispatcher so config writes never race each other.
