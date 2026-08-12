# Audio Engine & DSP Pipeline

This document describes how AmpChain turns an incoming audio stream into a
processed guitar tone: the real-time audio engine, its lock-free bridge to the
UI, and the DSP module pipeline that actually shapes the sound.

It is a companion to the [AmpChain Design Spec](AMPCHAIN_DESIGN_SPEC.md) and
reflects the current implementation under
`app/src/main/kotlin/org/ampsim/audio` and
`app/src/main/kotlin/org/ampsim/dsp`.

---

## Table of Contents

1. [Overview](#overview)
2. [Component Map](#component-map)
3. [Threading Model](#threading-model)
4. [The Command Queue](#the-command-queue)
5. [Real-Time Processing Loop](#real-time-processing-loop)
6. [DSP Pipeline](#dsp-pipeline)
7. [DSP Modules](#dsp-modules)
8. [LV2 Plugin Hosting](#lv2-plugin-hosting)
9. [From Model to Modules](#from-model-to-modules)
10. [Metering & Status](#metering--status)
11. [Real-Time Safety Rules](#real-time-safety-rules)
12. [Error Handling](#error-handling)
13. [Key Types Reference](#key-types-reference)

---

## Overview

The audio subsystem has one hard constraint: the **real-time audio thread must
never block and never allocate**. JACK calls back on a high-priority thread with
a fixed deadline (one audio block). Missing that deadline produces audible
glitches (xruns), so everything on that path is pre-allocated and wait-free.

To respect this, AmpChain splits work across two worlds:

- **Control/UI world** — builds DSP modules, reads presets, responds to widget
  events. Allowed to allocate and block.
- **Real-time world** — the JACK process callback. Only reads pre-built data,
  runs the DSP chain, and publishes meter levels.

The two communicate in one direction through a **lock-free command queue**, and
in the other direction through **`@Volatile` meter fields**. No locks are shared
between them.

```mermaid
graph LR
    UI["UI / Control Thread"] -->|AudioCommand| Q["LockFreeRingBuffer"]
    Q -->|drained per block| RT["RT Audio Thread"]
    RT -->|"@Volatile levels"| UI
    RT -->|process| DSP["DSP Chain"]
    JACK["JACK Server"] -->|process callback| RT
```

---

## Component Map

| Component | File                          | Responsibility |
|-----------|-------------------------------|----------------|
| `AudioEngine` | `audio/AudioEngine.kt`        | Owns the JACK client, the command queue, the active chain, and the RT callback. |
| `JackClient` | `audio/JackClient.kt`         | Thin wrapper over JNAJack: opens/activates the client, registers ports, routes connections. |
| `LockFreeRingBuffer<T>` | `audio/LockFreeRingBuffer.kt` | SPSC wait-free queue used as the UI→audio command channel. |
| `AudioCommand` | `audio/AudioCommand.kt`       | Sealed set of messages the UI sends to the audio thread. |
| `AudioStatus` | `audio/AudioStatus.kt`        | Immutable snapshot of connection state + metering for the UI. |
| `DSPModule` | `dsp/DSPModule.kt`            | Contract every effect implements. |
| `BaseDSPModule` | `dsp/BaseDSPModule.kt`        | Shared parameter storage, state serialization, buffer validation. |
| `DSPModuleFactory` | `dsp/DSPModuleFactory.kt`     | Builds fully-initialized modules from model objects, off the audio thread. |
| `GenericOverdrive` / `GenericAmp` / `GenericDelay` | `dsp/effects/ Generic*.kt`    | The concrete placeholder effects. |
| `LV2ModuleAdapter` | `dsp/LV2ModuleAdapter.kt`     | Wraps a live LV2 plugin instance as a `DSPModule`; fault-latches on any native failure. |
| `LV2PluginHost` / `LV2Discovery` / `LV2PluginCache` | `lv2/*.kt`      | Panama-FFI-backed LV2 host: per-instance native buffers/ports, system plugin scan, process-wide cache. |
| `Chain` / `EffectUnit` | `model/*.kt`                  | Serializable description of a signal chain (the "what"). |

The **model** describes a chain declaratively; the **DSP layer** turns it into
runnable modules; the **audio layer** runs them in real time.

---

## Threading Model

```mermaid
graph TB
    subgraph Control["Control / UI Thread (may allocate, may block)"]
        W["Widget events"]
        LE["AudioEngine.loadChain / updateParameter / setPlaybackEnabled"]
        F["DSPModuleFactory builds modules"]
        EN["enqueue -> commandQueue.offer"]
    end

    subgraph Queue["Lock-free bridge"]
        RB["LockFreeRingBuffer&lt;AudioCommand&gt;"]
        MET["@Volatile inputLevel / outputLevel"]
    end

    subgraph RT["JACK RT Thread (no alloc, no blocking)"]
        DR["drainCommands -> applyCommand"]
        PR["process(): run DSP chain"]
        PUB["publish RMS levels"]
    end

    W --> LE --> F --> EN --> RB
    RB --> DR --> PR --> PUB --> MET
    MET -.read.-> W
```

Rules enforced by this split:

- Only the **control thread** calls `offer()` on the queue (single producer).
- Only the **audio thread** calls `poll()` on the queue (single consumer).
- The active chain (`activeChain`) is **owned exclusively by the audio thread**.
  The UI never mutates it directly; it swaps the whole list via a
  `LoadChain` command.
- Meter values flow back through plain `@Volatile` fields — cheap, lock-free
  reads for the UI.

---

## The Command Queue

`LockFreeRingBuffer<T>` is a **single-producer / single-consumer (SPSC)**
wait-free ring buffer. Its backing array is allocated once at construction, so
`offer` and `poll` never allocate and never block.

Key properties:

- Capacity is fixed (`COMMAND_QUEUE_CAPACITY = 256` for the command queue). One
  extra internal slot distinguishes "full" from "empty".
- `offer(item)` returns `false` when full — the item is **dropped**, never
  queued unboundedly. This keeps memory bounded even under a flood of UI events.
- Correctness relies on the volatile publish/subscribe ordering of the
  `head`/`tail` `AtomicInteger`s: the producer fills a slot, *then* publishes the
  new tail; the consumer reads the tail, *then* reads the slot.

When `offer` fails, `AudioEngine.enqueue` increments an `AtomicLong` counter and
logs a warning; the count is surfaced through `AudioStatus.droppedCommands`.

```mermaid
sequenceDiagram
    participant UI as UI Thread
    participant Q as LockFreeRingBuffer
    participant RT as Audio Thread

    UI->>UI: build modules (DSPModuleFactory)
    UI->>Q: offer(LoadChain / SetParameter / ...)
    alt queue full
        Q-->>UI: false (drop + count++ + log)
    else queued
        Q-->>UI: true
    end
    Note over RT: next process() block boundary
    RT->>Q: poll() until empty
    Q-->>RT: AudioCommand
    RT->>RT: applyCommand(...)
```

Because commands are only drained at the **start of a block**, every change
takes effect on a block boundary — never mid-block. This is the core mechanism
that avoids clicks and pops on parameter changes and chain swaps.

### Command Types

| Command | Meaning | Applied by |
|---------|---------|-----------|
| `LoadChain(modules)` | Replace the entire active chain with pre-built modules. | `activeChain = modules` |
| `SetParameter(index, name, value)` | Update one parameter of the module at `index`. Out-of-range index ignored. | `module.setParameter(...)` |
| `ResetChain` | Clear internal state (filters, delay lines) of all modules. | `module.reset()` for each |
| `SetPlayback(enabled)` | Mute/unmute output (metering keeps running). | toggles `rtPlaybackEnabled` |
| `SetTestSignal(enabled)` | Toggle the internal 440 Hz test tone. | toggles `rtTestSignalEnabled` |

---

## Real-Time Processing Loop

The JACK client is configured in `JackClient.open()` with a process callback that
fetches the input/output `FloatBuffer`s and delegates to
`AudioEngine.process(input, output, nframes)`. That method is the entire
real-time critical section.

```mermaid
flowchart TD
    A["process(input, output, nframes)"] --> B["drainCommands() — apply all queued changes"]
    B --> C{"framesToCopy == 0?"}
    C -->|yes| Z["clear output, return"]
    C -->|no| D{"test signal on?"}
    D -->|yes| E["generate 440 Hz sine into input"]
    D -->|no| F
    E --> F["compute input RMS -> inputLevel"]
    F --> G{"activeChain empty?"}
    G -->|yes| H["pass input straight to output"]
    G -->|no| I["copy input into scratchA"]
    I --> J["runBlocking: chain modules, ping-pong scratchA/scratchB"]
    J --> K["copy result into output"]
    H --> L{"playback enabled?"}
    K --> L
    L -->|yes| M["compute output RMS -> outputLevel"]
    L -->|no| N["zero the output"]
    M --> O["clear trailing frames"]
    N --> O
```

Notable details:

- **`drainCommands()` runs first**, so the block is processed with the freshest
  UI state, and all effects land on the boundary.
- **Scratch buffers** (`scratchA`, `scratchB`) are pre-allocated to
  `DEFAULT_MAX_BLOCK = 8192` frames (and grown once at `start()` to at least the
  JACK buffer size). The chain "ping-pongs" between them so each module reads one
  buffer and writes the other — no per-module allocation.
- **`runBlocking` per block**: `DSPModule.process` is a `suspend` function, so the
  loop wraps the whole chain in a single `runBlocking`. The generic modules never
  actually suspend, so no coroutine dispatch or allocation happens per module.
- **Pass-through** when the chain is empty: input is copied straight to output.
- **Playback disabled** zeroes the output but still updates meters (output RMS
  will be 0), so the UI stays responsive.

---

## DSP Pipeline

Modules are chained **in series**: the output of one is the input to the next,
in list order. `AudioEngine` drives this with a two-buffer ping-pong so no
intermediate arrays are allocated.

```mermaid
graph LR
    IN["Input (JACK in)"] --> M0["Module[0]"]
    M0 --> M1["Module[1]"]
    M1 --> M2["Module[2]"]
    M2 --> OUT["Output (JACK out)"]
```

A representative full-rig ordering (overdrive → amp → delay) as produced by the
legacy dashboard toggles:

```mermaid
graph LR
    G["Guitar / Test tone"] --> OD["GenericOverdrive"]
    OD --> AMP["GenericAmp"]
    AMP --> DLY["GenericDelay"]
    DLY --> SPK["Speakers"]
```

Each module obeys the `DSPModule` contract:

- `process(input, output, nframes): Result<Unit>` — block processing; returns a
  failure `Result` if the buffers are too small or `nframes` is invalid (checked
  by `BaseDSPModule.validateBuffers`).
- `setParameter` / `getParameter` — parameters are clamped to their declared
  `ParameterInfo` range.
- `reset()` — clear filter/delay state to silence.
- `getState()` / `setState()` — serialize parameters to/from a `Map<String, Float>`.
- `getLatencySamples()` and `getCpuLoad()` — reporting hooks.

---

## DSP Modules

All three concrete modules extend `BaseDSPModule`, which stores parameter values
in a map keyed by name and falls back to each parameter's declared default. They
are **placeholders** for testing the pipeline, not models of specific hardware.

### GenericOverdrive (`type = "overdrive"`)

Signal path: drive gain → `tanh` soft-clip saturation → one-pole low-pass tone
control → output level.

| Parameter | Range | Default | Notes |
|-----------|-------|---------|-------|
| `drive` | 1–50 | 10 | Input gain into the saturator |
| `tone` | 0–1 | 0.5 | Blends filtered/unfiltered signal and sets filter coefficient |
| `level` | 0–1 | 0.7 | Output level |

State: one-pole low-pass accumulator (`lpState`), cleared on `reset()`.

### GenericAmp (`type = "amp"`)

Signal path: pre-gain → `tanh` saturation → 3-band tone stack (bass/mid/treble
via two one-pole crossovers) → master volume.

| Parameter | Range | Default | Notes |
|-----------|-------|---------|-------|
| `gain` | 1–100 | 20 | Pre-gain into saturation |
| `bass` | 0–2 | 1 | Low-band gain |
| `mid` | 0–2 | 1 | Mid-band gain |
| `treble` | 0–2 | 1 | High-band gain |
| `master` | 0–1 | 0.7 | Output level |

State: two one-pole crossover accumulators (`lowState`, `lowMidState`).

### GenericDelay (`type = "delay"`)

A single circular delay line with feedback and dry/wet mix. The delay buffer is
sized once for `MAX_DELAY_MS = 2000 ms`, so `process` never allocates.

| Parameter | Range | Default | Unit | Notes |
|-----------|-------|---------|------|-------|
| `time` | 1–2000 | 250 | ms | Delay time (converted to samples) |
| `feedback` | 0–0.95 | 0.3 | | Fraction fed back into the line |
| `mix` | 0–1 | 0.35 | | Dry/wet blend |

`getLatencySamples()` returns the current delay time in samples; the others
report 0.

### LV2ModuleAdapter (`type = "lv2:<plugin URI>"`)

Wraps a live LV2 plugin instance ([`LV2PluginHost`](#lv2-plugin-hosting)) as a
`DSPModule`. Parameters are the plugin's control ports (named by their LV2
*symbol*, a spec-stable per-plugin identifier — not the display name, which
can be reworded across plugin versions). `process()` catches any exception
from the native call and latches a `faulted` flag: once faulted, every
subsequent block outputs silence without calling into the plugin again,
rather than re-triggering the same failure every block. `reset()` calls the
plugin's `activate`/`deactivate` functions, which LV2 documents as not
real-time safe — an accepted, bounded-frequency compromise since a reset is
a rare user action, not a per-block one. `getLatencySamples()`/`getCpuLoad()`
are not measured for LV2 plugins (return 0) — a known v1 limitation.

`dispose()` frees the plugin's native `Arena` and instance handle; see
[Real-Time Safety Rules](#real-time-safety-rules) below for why this can
never happen inline during a chain swap.

---

## LV2 Plugin Hosting

LV2 plugins are hosted via [liblilv](https://gitlab.com/lv2/lilv) (the
standard LV2 host C library, which does Turtle/RDF manifest parsing,
discovery, and instantiation) bound through Java 25's Panama FFI
(`java.lang.foreign`) — not JNI, which this build has no tooling for.

| Component | File | Responsibility |
|-----------|------|-----------------|
| `LilvNative` | `lv2/ffi/LilvNative.kt` | Raw symbol-bound `MethodHandle`s for every exported liblilv function. `available` gates every downstream entry point. |
| `LilvInstanceCalls` | `lv2/ffi/LilvInstanceCalls.kt` | `lilv_instance_connect_port`/`activate`/`run`/`deactivate` are `static inline` in lilv.h and have **no exported symbol** — this replicates the inline C code by reading the plugin's `LV2_Descriptor` function-pointer table directly out of the `LilvInstance*` struct and invoking through it. `run` is bound with `Linker.Option.critical` since it's the one call made from the RT thread. |
| `LilvWorld` / `LilvPluginRef` / `LilvPortRef` | `lv2/Lilv*.kt` | Memory-safe Kotlin wrappers; no raw `MemorySegment` leaks above this layer. |
| `LV2PortTopology` | `lv2/LV2PortTopology.kt` | Pure `isSupportedTopology(kinds)`: exactly 1 audio-in + 1 audio-out, no CV/Atom ports — this app is mono end-to-end (v1), so stereo/multi-channel plugins are skipped, not treated as errors. |
| `LV2Discovery` / `LV2PluginCache` | `lv2/LV2Discovery.kt`, `lv2/LV2PluginCache.kt` | One process-lifetime `LilvWorld` scan (`lilv_world_load_all`, respects `$LV2_PATH`), classifying and caching each plugin. A plugin that's unreadable or fails a throwaway smoke-instantiate is skipped individually, never aborting the whole scan. |
| `LV2PluginHost` | `lv2/LV2PluginHost.kt` | Owns one live instance: a shared `Arena` (constructed on the control thread, `run()` called from the RT thread — must be `Arena.ofShared`, not confined), pre-allocated native audio/control port buffers, connected once at construction. |
| `LV2InitTimeout` | `lv2/LV2InitTimeout.kt` | Bounds the world scan and per-plugin instantiation with a timeout on a daemon-thread executor; a timeout abandons (never cancels — Panama gives no safe way to) the in-flight call rather than blocking the caller. |

`LV2PluginCache.refresh()` (a full disk scan) runs once in the background via
`App.kt`'s `uiCoroutineScope.launch(Dispatchers.IO)` right after
`audioEngine.start()`, never on the GTK main thread; the Library panel shows
just the built-ins until it completes.

---

## From Model to Modules

The model layer (`Chain`, `EffectUnit`) is a **serializable description** of a
rig. `DSPModuleFactory` converts it into runnable `DSPModule` instances **off the
audio thread**, so the audio thread only ever receives ready-to-run objects.

```mermaid
flowchart LR
    P["Preset / Chain (model)"] --> EU["EffectUnit list"]
    EU -->|enabledUnits| FAC["DSPModuleFactory.createChain"]
    FAC -->|create + apply params| MODS["List&lt;DSPModule&gt;"]
    MODS -->|LoadChain command| ENG["AudioEngine.loadChain"]
    ENG --> Q["command queue"]
    Q --> RT["audio thread swaps activeChain"]
```

- `DSPModuleFactory.supportedTypes` = `{ "overdrive", "amp", "delay", "reverb", "chorus" }`
  — the built-ins only. A type prefixed `"lv2:"` (see `LV2PluginInfo.LV2_TYPE_PREFIX`)
  is dispatched to `LV2ModuleAdapter.create` instead, deliberately kept
  outside `supportedTypes` so the type is never lower-cased (LV2 URIs are
  case-sensitive, unlike the built-in branch).
- `create(type)` maps a type string to a fresh module; unknown types (built-in
  or an unresolvable LV2 URI) return `null`.
- `create(unit)` builds the module and applies each stored parameter.
- `createChain(chain)` maps the chain's **enabled** units to modules, **skipping
  unknown types** so a malformed preset — or an LV2 plugin uninstalled since
  the preset was saved — can't break chain loading.

`AudioEngine.loadChain(chain)` runs the factory at the current sample rate, then
enqueues a single `LoadChain` command. The legacy dashboard toggles
(`setOverdriveEnabled` etc.) build their chain the same way via
`rebuildLegacyChain()`.

---

## Metering & Status

The audio thread computes **RMS** of the input (post test-signal) and output
each block and stores them in `@Volatile` `inputLevel` / `outputLevel` fields.
The UI reads them lock-free via `getInputLevel()` / `getOutputLevel()` /
`getVolume()`.

`AudioStatus` is an immutable snapshot assembled on demand by `updateStatus()`:

| Field | Source |
|-------|--------|
| `isConnected`, `sampleRate`, `bufferSize`, `cpuLoad`, `clientName` | `JackClient` |
| `inputLevel`, `outputLevel`, `activeModules` | audio thread meter/chain state |
| `droppedCommands` | dropped-command counter |
| `lastError` | last recorded error message |

Because metering uses `@Volatile` publication rather than a lock, the UI can poll
levels at frame rate without ever contending with the audio thread.

---

## Real-Time Safety Rules

These invariants are what keep the engine glitch-free. Preserve them when
extending the audio path:

1. **Never allocate on the audio thread.** All buffers (`scratchA/B`, delay
   lines, the ring buffer) are pre-allocated. Build new modules on the control
   thread and hand them over via `LoadChain`.
2. **Never block on the audio thread.** No locks, no I/O, no unbounded waits. The
   only synchronization is the wait-free queue and `@Volatile` fields.
3. **Single producer, single consumer.** Only the control thread `offer`s;
   only the audio thread `poll`s and owns `activeChain`.
4. **Apply changes on block boundaries.** All commands are drained at the top of
   `process()`, so parameter and chain changes never take effect mid-block.
5. **Bounded queues.** A full queue drops commands (and counts them) rather than
   growing without limit.
6. **Modules must be RT-friendly.** `process` must not allocate; stateful modules
   must implement `reset()` to clear buffers on `ResetChain`.
7. **Native calls from `process()` must be pre-bound and non-allocating.**
   `LV2ModuleAdapter`'s native `run()` call is bound to a `MethodHandle`
   once, at `LV2PluginHost` construction (off the RT thread) — never
   re-resolved per block. Native resource teardown (`dispose()`, which frees
   an LV2 plugin's `Arena`) never happens inline during a `LoadChain` swap:
   `AudioEngine` retires the outgoing chain into a second bounded
   `LockFreeRingBuffer<List<DSPModule>>` (`retiredModules`, RT thread
   producer) that the control thread drains via `pollRetiredModules()` on
   the same 50ms timer that already polls metering — see `App.kt`.

---

## Error Handling

- **JACK startup failures** are translated by `JackClient.open()` into a
  descriptive `Exception` (server not found / not started / connection failed).
  `AudioEngine.start()` catches it, records `lastError`, marks the status
  disconnected, and logs at `SEVERE`.
- **Device not found**: `setInputDevice()` checks the requested source against
  `availableInputSources()` and records a `lastError` (and warns) if it is
  missing, while still attempting routing with a sensible fallback.
- **Dropped commands**: surfaced via the counter + `AudioStatus.droppedCommands`
  and a warning log, so a UI event flood is observable rather than silent.
- **Invalid process buffers**: `BaseDSPModule.validateBuffers` returns a failure
  `Result` for negative frame counts or under-sized buffers instead of reading
  out of bounds.
- **Missing liblilv**: `LilvNative.available` is `false` when the library
  can't be found; every LV2 entry point (discovery, instantiation) checks it
  first and degrades to "no LV2 plugins" rather than crashing.
- **Unsupported/corrupted LV2 plugins**: `LV2Discovery` skips a plugin
  individually — wrong port topology, unreadable manifest, or a failed
  smoke-test instantiation — rather than aborting the whole scan, mirroring
  `DSPModuleFactory.createChain`'s "skip unknown types" behavior.
- **LV2 instantiation timeout/failure**: `LV2ModuleAdapter.create` bounds
  `LV2PluginHost` construction with `LV2InitTimeout` and returns `null` on
  any failure — a preset referencing an uninstalled or slow-to-load plugin
  degrades exactly like an unknown built-in type.
- **LV2 runtime faults**: `LV2ModuleAdapter.process()` catches any exception
  from the native call and latches a `faulted` flag — silence from then on,
  not a crash, and not a repeated failure every block. This only covers
  JVM-level failures in the FFI plumbing; it cannot protect against the
  plugin's native code segfaulting, which terminates the whole JVM process —
  an inherent risk of hosting any native plugin format, already implicit in
  running with `--enable-native-access`.

Logging uses `java.util.logging.Logger` throughout the audio engine.

---

## Key Types Reference

| Type | Kind | Summary |
|------|------|---------|
| `AudioEngine` | class | Real-time engine; JACK callback, command draining, metering, lifecycle (`start`/`stop`). |
| `JackClient` | class | JNAJack wrapper: open/activate/close, port registration, routing, sample/buffer info. |
| `JackClient.AudioProcessor` | interface | `process(input, output, nframes)` callback contract implemented by `AudioEngine`. |
| `LockFreeRingBuffer<T>` | class | SPSC wait-free queue (`offer`/`poll`/`isEmpty`/`isFull`/`count`). |
| `AudioCommand` | sealed interface | `LoadChain`, `SetParameter`, `ResetChain`, `SetPlayback`, `SetTestSignal`. |
| `AudioStatus` | data class | Immutable state + metering snapshot for the UI. |
| `DSPModule` | interface | Effect contract: `process`, parameters, `reset`, state, latency/CPU. |
| `BaseDSPModule` | abstract class | Shared parameter map, state (de)serialization, buffer validation; `DEFAULT_SAMPLE_RATE = 48000`. |
| `DSPModuleFactory` | object | Builds modules from `EffectUnit`/`Chain` off the audio thread; dispatches `"lv2:"`-prefixed types to `LV2ModuleAdapter.create`. |
| `LV2ModuleAdapter` | class | `DSPModule` wrapping a live LV2 plugin instance; fault-latches on any native failure. |
| `LV2PluginHost` | class | Owns one live liblilv instance: shared `Arena`, pre-allocated native port buffers. |
| `LV2Discovery` / `LV2PluginCache` | object | System LV2 plugin scan (via liblilv) and its process-wide cache. |
| `ParameterInfo` | data class | Parameter metadata: `min`/`max`/`default`/`unit`, with `clamp`. |
| `Chain` / `EffectUnit` | data classes | Serializable declarative description of a rig. |
