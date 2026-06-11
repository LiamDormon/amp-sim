# AmpChain: Development Roadmap & Jira Tickets

**Status:** Ready for Sprint Planning  
**Last Updated:** June 2026

---

## Executive Summary

This document breaks down the AmpChain software design specification into prioritized, actionable tickets organized by:

1. **MVP Phase** - Core features required for alpha release
2. **Sprint 1** - Essential features and first complete workflow
3. **Sprint 2** - UI polish and expanded functionality
4. **Sprint 3** - Performance optimization and advanced features
5. **Future Enhancements** - Nice-to-have features for later releases

Each ticket is sized in Fibonacci points (1, 2, 3, 5, 8, 13) and includes acceptance criteria suitable for Jira.

---

## MVP Phase (Weeks 1-4)

**Goal:** Establish core architecture, basic UI, and proof-of-concept audio processing

### AMP-1: Project Setup & Build Configuration

**Type:** Task  
**Story Points:** 5  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Set up Kotlin/Gradle project with all necessary dependencies, build configuration, and CI/CD pipeline.

**Acceptance Criteria:**
- [ ] Gradle build.gradle.kts created with all core dependencies (Kotlin, Coroutines, Serialization)
- [ ] GTK4/libadwaita bindings configured and compiling
- [ ] JACK audio library integrated
- [ ] Testing dependencies (Kotest, Mockk) configured
- [ ] GitHub Actions workflow created for automated builds
- [ ] Project structure follows defined layout
- [ ] Build succeeds on both Linux (GNOME/KDE) and CI environment

**Implementation Notes:**
- Use Kotlin 1.9.20+
- Set JVM target to 17
- Enable experimental features (inline classes)
- Configure Spotless for code formatting

**Related Issues:** None (foundational)

---

### AMP-2: Core Domain Models & Data Structures

**Type:** Story  
**Story Points:** 8  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Implement core data models (Preset, Chain, Unit, Parameter) with serialization support using Kotlinx Serialization.

**Acceptance Criteria:**
- [ ] Chain data class with units list and manipulation methods (add, remove, move, update)
- [ ] Unit data class with id, type, model, enabled flag, and parameters map
- [ ] Parameter data class with name, value, min, max, step
- [ ] Preset data class with metadata (name, description, created, modified, tags, author)
- [ ] PresetMetadata data class with all required fields
- [ ] Type aliases for UnitId, PresetName, ParameterValue
- [ ] All classes marked with @Serializable for JSON encoding
- [ ] Extension methods on Chain for filtering, searching, validation
- [ ] Unit tests covering:
  - Chain operations (add, remove, reorder)
  - Parameter clamping and validation
  - Serialization/deserialization roundtrip
  - Immutability guarantees

**Implementation Notes:**
- Use Kotlinx Serialization, not Gson
- Implement proper null safety
- Add builder patterns for complex object creation
- Prefer data classes and value classes (inline)

**Related Issues:** AMP-1

---

### AMP-3: Configuration Management & Persistence

**Type:** Story  
**Story Points:** 5  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Implement ConfigManager using coroutines and StateFlow for reactive configuration updates. Support XDG Base Directory spec.

**Acceptance Criteria:**
- [ ] ConfigManager loads/saves to ~/.config/ampchain/config.json
- [ ] AppConfig, AudioConfig, UIConfig data classes serializable
- [ ] StateFlow<AppConfig> provides reactive config access
- [ ] Coroutine-based async load/save without blocking UI
- [ ] Config validates and provides sensible defaults
- [ ] Config directories created on first run (XDG compliant)
- [ ] Unit tests covering:
  - Config loading and saving
  - Default values when file missing
  - Corrupted file handling (fallback to defaults)
  - Config updates trigger persistence
  - Concurrent update safety

**Implementation Notes:**
- Use Dispatchers.IO for file operations
- Implement proper error logging with KotlinLogging
- Add config migration support for future versions

**Related Issues:** AMP-1, AMP-2

---

### AMP-4: Basic GTK UI Window Setup

**Type:** Story  
**Story Points:** 8  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Create main application window with GTK4/libadwaita, basic sidebar navigation, and tab switching.

**Acceptance Criteria:**
- [ ] Main application window using AdwApplicationWindow
- [ ] Sidebar with 5 navigation tabs (Dashboard, Chain Editor, Presets, Library, Settings)
- [ ] Tab switching works via buttons and Alt+1-5 keyboard shortcuts
- [ ] Window remembers size and position
- [ ] Responsive layout that works at 800x600 and higher
- [ ] LibAdwaita theme integration (respects system dark/light mode)
- [ ] Integration tests verify:
  - Window opens and closes cleanly
  - Tabs switch correctly
  - Keyboard shortcuts work
  - Theme changes are applied

**Implementation Notes:**
- Use libadwaita 1.x components exclusively
- Implement window state serialization in config
- Plan for future placeholder content areas

**Related Issues:** AMP-1, AMP-3

---

### AMP-5: DSP Module Interface & Generic Effects

**Type:** Story  
**Story Points:** 8  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Define DSPModule interface and implement 3 generic placeholder effects (Overdrive, Delay, Amp) for testing.

**Acceptance Criteria:**
- [ ] DSPModule interface with suspend process() function returning Result<Unit>
- [ ] BaseDSPModule abstract class with parameter management
- [ ] GenericOverdrive implementation with drive, tone, level parameters
- [ ] GenericDelay implementation with time, feedback, mix parameters
- [ ] GenericAmp implementation with gain, bass, mid, treble, master parameters
- [ ] ParameterInfo data class for parameter metadata
- [ ] Each DSP module has:
  - Proper parameter ranges and defaults
  - State serialization (getState/setState)
  - Latency reporting
  - CPU load estimation
- [ ] Unit tests for each DSP module:
  - Parameter setting/getting
  - Process block execution
  - State save/restore
  - Latency calculation

**Implementation Notes:**
- Use simple DSP algorithms (tanh saturation, basic filters)
- Provide deterministic, repeatable processing
- Optimize for speed in audio thread (no allocations)
- Generic implementations suitable for testing only

**Related Issues:** AMP-1, AMP-2

---

### AMP-6: Audio Engine & JACK Client

**Type:** Story  
**Story Points:** 13  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Implement AudioEngine with JACK client integration, real-time audio thread, and lock-free inter-thread communication.

**Acceptance Criteria:**
- [ ] AudioEngine class with JACK client initialization
- [ ] Real-time audio thread using JACK callback
- [ ] Lock-free ring buffer (LockFreeRingBuffer<T>) for command queue
- [ ] Command queue for UI→Audio communication (process on block boundaries)
- [ ] Safe DSP module loading and parameter updates in real-time thread
- [ ] Input/output metering without lock contention
- [ ] AudioStatus data class tracking connection state
- [ ] Error handling for device not found, initialization failures
- [ ] Integration tests verify:
  - Audio processes correctly through DSP chain
  - No clicks/pops on parameter changes
  - Parameter updates apply on block boundaries
  - Rapid chain updates don't crash
  - Memory doesn't grow unbounded

**Implementation Notes:**
- Use atomic operations for lock-free communication
- Pre-allocate all buffers before audio thread starts
- Never allocate in real-time thread
- Use suspend functions for audio operations where safe
- Implement detailed error logging

**Related Issues:** AMP-1, AMP-2, AMP-5

---

### AMP-7: Chain Editor Canvas - Basic Layout

**Type:** Story  
**Story Points:** 8  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Create interactive Chain Editor canvas showing signal flow with placeholder units and basic drag-and-drop.

**Acceptance Criteria:**
- [ ] Canvas widget displaying linear signal flow (top to bottom)
- [ ] Unit widgets render with basic styling (name, type, enable/bypass toggle)
- [ ] Drag-and-drop reordering of units works
- [ ] Right-click context menu on units (stub implementation)
- [ ] Add unit button opens library (stub)
- [ ] Visual feedback for drag operations (highlight valid drop zones)
- [ ] Signal flow updates in real-time as units are reordered
- [ ] UI tests verify:
  - Units display correctly
  - Dragging reorders units
  - Context menu appears
  - Visual feedback works

**Implementation Notes:**
- Use GTK4 DrawingArea or custom widget
- Implement skeumorphic styling with CSS
- Keep rendering performant (avoid redraw every frame)
- Plan for complex chains (10+ units)

**Related Issues:** AMP-1, AMP-4, AMP-6

---

### AMP-8: EventBus & Reactive State Management

**Type:** Story  
**Story Points:** 5  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement event bus using Kotlin Flows for reactive UI updates and inter-component communication.

**Acceptance Criteria:**
- [ ] UIEventBus interface with Flow<UIEvent> and publish() function
- [ ] Sealed class UIEvent with event types:
  - ChainModified
  - PresetLoaded
  - ParameterChanged
  - AudioStatusChanged
  - UnitAdded/UnitRemoved
  - ErrorOccurred
- [ ] Extension functions for subscribing to specific event types
- [ ] EventBusImpl using Channel with buffer
- [ ] ChainManager publishes events on modifications
- [ ] Tests verify:
  - Events published correctly
  - Subscribers receive events
  - Event types filter correctly
  - No event loss under load

**Implementation Notes:**
- Use coroutines Channel for thread-safe event delivery
- Provide extension functions for convenient subscription
- Design events to be immutable value objects

**Related Issues:** AMP-2, AMP-6

---

## Sprint 1 (Weeks 5-8)

**Goal:** Complete core user workflows and achieve MVP functionality

### AMP-9: Preset Management - Save/Load

**Type:** Story  
**Story Points:** 8  
**Priority:** Critical  
**Assignee:** [TBD]  

**Description:**
Implement preset saving/loading with file I/O, preset repository pattern, and crossfade transitions.

**Acceptance Criteria:**
- [ ] PresetRepository interface for persistence operations
- [ ] FileSystemPresetRepository implementation:
  - Saves presets to ~/.local/share/ampchain/presets/
  - Loads presets from disk
  - Validates preset files
  - Handles errors gracefully
- [ ] Preset serialization/deserialization roundtrip works
- [ ] SavePresetDialog to capture name, description, author
- [ ] Current preset name displayed in header
- [ ] Automatic crossfade transition (200ms) when loading preset
- [ ] Auto-save service saves chain state every 30 seconds
- [ ] Tests cover:
  - Save and load presets
  - Corrupted file handling
  - Concurrent save/load safety
  - Auto-save functionality
  - Crossfade audio continuity

**Implementation Notes:**
- Store presets as JSON in user's local share directory
- Implement preset versioning support
- Add metadata (created, modified dates)
- Handle missing or inaccessible directories

**Related Issues:** AMP-2, AMP-3, AMP-6

---

### AMP-10: Presets Tab UI

**Type:** Story  
**Story Points:** 5  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Create Presets view with list, search, filtering, and context menu actions.

**Acceptance Criteria:**
- [ ] Presets listed in searchable ListView
- [ ] Search filters by name and description
- [ ] Tag-based filtering
- [ ] Context menu: Load, Rename, Duplicate, Export, Delete
- [ ] Double-click to load preset
- [ ] Confirmation dialog before delete
- [ ] Recent presets section at top
- [ ] UI tests verify:
  - Presets display correctly
  - Search filters work
  - Context menu actions trigger
  - Confirm before delete

**Implementation Notes:**
- Use Flow to bind preset list to UI
- Implement search debouncing (300ms)
- Use LibAdwaita list patterns

**Related Issues:** AMP-4, AMP-9

---

### AMP-11: Library Tab - Browse Amps & Effects

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Create Library view showing available DSP modules, searchable and drag-droppable to chain.

**Acceptance Criteria:**
- [ ] Library organized by category (Amps, Overdrives, Delays, Reverbs, Modulations)
- [ ] Each category expandable/collapsible
- [ ] Search across all categories
- [ ] Drag units from library to chain canvas
- [ ] Unit details panel shows:
  - Name, type, description
  - Parameters and ranges
  - Latency and CPU load
  - Quick preview controls
- [ ] Unit metadata loaded from discoverable sources
- [ ] UI tests verify:
  - Categories display
  - Search works
  - Drag-drop adds unit to chain
  - Details panel updates

**Implementation Notes:**
- Extend from DSPModule interface
- Lazy-load unit details
- Cache module list for performance

**Related Issues:** AMP-5, AMP-4, AMP-7

---

### AMP-12: Parameter Editor Panel

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement detailed parameter editor showing all DSP module parameters with appropriate widgets.

**Acceptance Criteria:**
- [ ] When unit selected in chain, parameter panel appears
- [ ] Parameter widgets by type:
  - Slider for continuous (LINEAR, LOG)
  - Toggle switch for boolean
  - Dropdown for choice parameters
  - Text field for string/numeric values
- [ ] Real-time value updates sent to audio engine
- [ ] Min/max/step constraints enforced
- [ ] Visual feedback for parameter changes
- [ ] A/B comparison mode (toggle between two sets)
- [ ] Parameter values synchronized with DSP module state
- [ ] Tests verify:
  - Parameter updates send events
  - Audio engine receives updates
  - UI reflects current values
  - A/B mode works correctly

**Implementation Notes:**
- Use builder pattern for widget creation
- Debounce parameter updates (30ms)
- Show parameter labels and units
- Preview changes in real-time

**Related Issues:** AMP-4, AMP-6, AMP-8

---

### AMP-13: Dashboard Tab - Quick Stats & Controls

**Type:** Story  
**Story Points:** 5  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Create Dashboard tab showing quick stats, meters, and quick-action buttons.

**Acceptance Criteria:**
- [ ] Display current preset name (with unsaved indicator *)
- [ ] Show active unit count
- [ ] CPU load percentage with real-time update
- [ ] Input/output signal level VU meters
- [ ] Audio backend status indicator (JACK: connected/disconnected)
- [ ] Quick buttons: New, Save, Load, Settings
- [ ] Recent presets carousel (last 5)
- [ ] Stats update every 100ms without lag
- [ ] Tests verify:
  - Meters update correctly
  - Buttons trigger actions
  - UI remains responsive under load

**Implementation Notes:**
- Use AnimatedValue for smooth meter updates
- Collect metrics from AudioEngine
- Display CPU load as percentage

**Related Issues:** AMP-4, AMP-6, AMP-9

---

### AMP-14: Settings Tab - Audio Configuration

**Type:** Story  
**Story Points:** 5  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Create Settings view for audio device selection, backend selection, and UI preferences.

**Acceptance Criteria:**
- [ ] Audio device selectors (input/output)
- [ ] Sample rate selector (44.1k, 48k, 96k)
- [ ] Buffer size selector (64, 128, 256, 512)
- [ ] Audio backend selector (JACK, PulseAudio)
- [ ] Theme selector (Light, Dark, System)
- [ ] CPU monitoring toggle
- [ ] Latency compensation toggle
- [ ] Auto-save interval slider
- [ ] Changes persist to config
- [ ] Audio engine restarts on device/backend change
- [ ] Tests verify:
  - Settings persist
  - Audio changes take effect
  - UI updates on config change

**Implementation Notes:**
- Enumerate available audio devices on startup
- Show backend status and connection state
- Require audio engine restart for some changes
- Show warning for potentially breaking changes

**Related Issues:** AMP-3, AMP-4, AMP-6

---

### AMP-15: Keyboard Shortcuts & Accessibility

**Type:** Story  
**Story Points:** 3  
**Priority:** Medium  
**Assignee:** [TBD]  

**Description:**
Implement keyboard shortcuts and basic accessibility features.

**Acceptance Criteria:**
- [ ] Tab switching: Alt+1 (Dashboard), Alt+2 (Chain), Alt+3 (Presets), Alt+4 (Library), Alt+5 (Settings)
- [ ] Common actions: Ctrl+S (Save), Ctrl+L (Load), Ctrl+N (New), Ctrl+Z (Undo)
- [ ] Navigation: Tab, Shift+Tab, Arrows for navigation
- [ ] Screen reader support (LibAdwaita provides much)
- [ ] High contrast mode compatible
- [ ] Keyboard-only operation possible
- [ ] Tests verify keyboard navigation works

**Implementation Notes:**
- Use GTK key event handlers
- Register global accelerators in app
- Provide descriptive labels for accessibility

**Related Issues:** AMP-4

---

### AMP-16: Chain Manager & Undo/Redo

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement ChainManager with operations (add, remove, reorder, update) and basic undo/redo stack.

**Acceptance Criteria:**
- [ ] ChainManager class with StateFlow<Chain>
- [ ] Operations: addUnit, removeUnit, reorderUnits, setParameterValue
- [ ] All operations publish UIEvents
- [ ] Undo stack stores previous chain states
- [ ] Redo stack maintains undone operations
- [ ] Undo/Redo triggers via Ctrl+Z/Ctrl+Shift+Z
- [ ] Max 50 undo states kept
- [ ] Tests verify:
  - Operations update chain correctly
  - Undo/Redo work properly
  - Events published on changes
  - Max stack size enforced

**Implementation Notes:**
- Store immutable chain snapshots on each operation
- Use sealed class for undo commands
- Limit memory usage with max stack size

**Related Issues:** AMP-2, AMP-6, AMP-8

---

## Sprint 2 (Weeks 9-12)

**Goal:** UI polish, expanded features, and performance optimization

### AMP-17: Skeumorphic Unit Widget Styling

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Enhance unit widgets with skeumorphic styling, visual depth, and interactive feedback.

**Acceptance Criteria:**
- [ ] Unit widgets styled with:
  - Beveled edges (CSS box-shadow gradients)
  - Metallic texture (radial gradients)
  - LED indicators (small glowing circles)
  - Grip area for drag handle
- [ ] Visual feedback on interaction:
  - Highlight when selected
  - Pressed effect on button click
  - Smooth transitions
- [ ] Support both light and dark themes
- [ ] Responsive sizing for different layouts
- [ ] Performance: no jank at 60fps with 20+ units
- [ ] Tests verify:
  - Widgets render correctly
  - Theme changes apply smoothly
  - No memory leaks on repeated style changes

**Implementation Notes:**
- Use GTK CSS exclusively (no image assets initially)
- Pre-compile CSS for performance
- Test at multiple DPI scales

**Related Issues:** AMP-7, AMP-4

---

### AMP-18: LV2 Plugin Support (Optional)

**Type:** Story  
**Story Points:** 13  
**Priority:** Medium  
**Assignee:** [TBD]  

**Description:**
Add LV2 plugin host support for loading external DSP plugins.

**Acceptance Criteria:**
- [ ] LV2ModuleAdapter class wrapping LV2 plugins
- [ ] Discovery of LV2 plugins from system locations
- [ ] Dynamic plugin loading and instantiation
- [ ] Parameter discovery from LV2 metadata
- [ ] Audio processing through LV2 in real-time thread
- [ ] State save/load for LV2 plugins
- [ ] Library shows both built-in and LV2 modules
- [ ] Error handling for invalid/corrupted plugins
- [ ] Tests verify:
  - Plugin discovery works
  - Audio processes through plugin
  - Parameters update correctly
  - State save/restore works

**Implementation Notes:**
- Use JNI to interface with LV2 C library
- Cache discovered plugins
- Implement timeout for plugin initialization
- Handle plugin crashes gracefully

**Related Issues:** AMP-5, AMP-11

---

### AMP-19: Export/Import Presets

**Type:** Story  
**Story Points:** 3  
**Priority:** Medium  
**Assignee:** [TBD]  

**Description:**
Add ability to export presets as shareable files and import from external sources.

**Acceptance Criteria:**
- [ ] Export preset opens file chooser
- [ ] Exported file is JSON with all preset data
- [ ] Import opens file chooser
- [ ] Imported presets validated and added to library
- [ ] Conflict handling (duplicate names)
- [ ] Tests verify:
  - Export/import roundtrip works
  - Invalid files rejected gracefully
  - Duplicates handled

**Implementation Notes:**
- Add file chooser dialogs
- Validate imported JSON before loading
- Show import summary

**Related Issues:** AMP-9, AMP-10

---

### AMP-20: Copy/Paste Unit Support

**Type:** Story  
**Story Points:** 5  
**Priority:** Medium  
**Assignee:** [TBD]  

**Description:**
Add ability to copy/paste units and their parameters within the chain.

**Acceptance Criteria:**
- [ ] Right-click Copy on unit
- [ ] Right-click Paste in chain (inserts after selected)
- [ ] Ctrl+C/Ctrl+V shortcuts work
- [ ] Pasted units get new IDs
- [ ] Parameters copied correctly
- [ ] Multiple copies possible
- [ ] Tests verify copy/paste functionality

**Implementation Notes:**
- Store copied unit in clipboard variable
- Show paste option only when unit available
- Generate new UUIDs for copied units

**Related Issues:** AMP-7, AMP-8

---

### AMP-21: Settings - Advanced Options & Profiles

**Type:** Story  
**Story Points:** 5  
**Priority:** Low  
**Assignee:** [TBD]  

**Description:**
Add advanced settings (real-time priority, NUMA awareness) and configuration profiles.

**Acceptance Criteria:**
- [ ] Real-time thread priority selector (0-99)
- [ ] CPU affinity settings
- [ ] Buffer pre-allocation controls
- [ ] Configuration profiles (save/load settings)
- [ ] Debug logging toggle
- [ ] Tests verify settings persist

**Implementation Notes:**
- Use capabilities system for real-time
- Show warnings for privileged operations
- Store profiles separately

**Related Issues:** AMP-14

---

### AMP-22: Performance Monitoring & Metrics

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Add detailed performance metrics (CPU load, latency, memory) with UI display.

**Acceptance Criteria:**
- [ ] CPU load per unit tracking
- [ ] Total chain latency calculation
- [ ] Memory usage monitoring
- [ ] Metrics update every 100ms
- [ ] Dashboard shows graphs
- [ ] Settings option to log metrics to file
- [ ] Tests verify accuracy of measurements

**Implementation Notes:**
- Use timing counters in audio thread
- Implement lock-free metric updates
- Display smoothed values (avoid jitter)

**Related Issues:** AMP-6, AMP-13

---

### AMP-23: Drag-and-Drop Refinements

**Type:** Story  
**Story Points:** 5  
**Priority:** Medium  
**Assignee:** [TBD]  

**Description:**
Enhance drag-and-drop with visual feedback, drop zone indication, and multi-select.

**Acceptance Criteria:**
- [ ] Drop zone highlight shows where unit will land
- [ ] Drag image shows unit being moved
- [ ] Multi-select with Ctrl+Click possible
- [ ] Drag multiple selected units together
- [ ] Drag from library shows preview
- [ ] Smooth scrolling on edge detection
- [ ] Tests verify drag-drop interactions

**Implementation Notes:**
- Use GTK drag-drop events
- Animate drop position indicator
- Handle edge cases (drag outside window)

**Related Issues:** AMP-7, AMP-11

---

### AMP-24: Audio Clipping & Overload Detection

**Type:** Story  
**Story Points:** 5  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Detect audio clipping and signal overload, provide visual and audio feedback.

**Acceptance Criteria:**
- [ ] Monitor output level for clipping (>0dBFS)
- [ ] LED indicator in status bar turns red on clip
- [ ] Click sound plays on first clip per 2 seconds (optional)
- [ ] Clipping count shown in metrics
- [ ] Headroom warning at -3dB
- [ ] Tests verify clipping detection accuracy

**Implementation Notes:**
- Track peak level in real-time
- Use atomic values for thread safety
- Show LED animation on clip

**Related Issues:** AMP-6, AMP-13

---

## Sprint 3 (Weeks 13-16)

**Goal:** Performance optimization, testing coverage, and release preparation

### AMP-25: Comprehensive Test Suite - Unit Tests

**Type:** Story  
**Story Points:** 13  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement comprehensive unit tests using Kotest, covering all core components.

**Acceptance Criteria:**
- [ ] ChainManager tests (add, remove, reorder, update operations)
- [ ] Parameter clamping and validation tests
- [ ] Preset serialization roundtrip tests
- [ ] ConfigManager load/save tests
- [ ] DSP module parameter tests
- [ ] Lock-free ring buffer tests
- [ ] Event publishing tests
- [ ] 80%+ code coverage for core modules
- [ ] All tests use Kotest DSL
- [ ] Tests run in <5 seconds

**Implementation Notes:**
- Use Kotest specs for readability
- Property-based tests for invariant checking
- Mock external dependencies

**Related Issues:** AMP-2, AMP-3, AMP-6, AMP-8, AMP-16

---

### AMP-26: Integration Tests - Audio Pipeline

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement integration tests verifying end-to-end audio processing.

**Acceptance Criteria:**
- [ ] Test audio flows through chain correctly
- [ ] Test preset switching without clicks
- [ ] Test parameter updates apply without artifacts
- [ ] Test chain with 20+ units remains low latency
- [ ] Test CPU load tracking is accurate
- [ ] Test rapid chain modifications don't crash
- [ ] All tests deterministic and repeatable

**Implementation Notes:**
- Generate known test signals
- Verify output characteristics
- Measure latency accurately

**Related Issues:** AMP-6, AMP-22

---

### AMP-27: UI Tests - Complete Workflows

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement comprehensive UI tests using TestFX covering user workflows.

**Acceptance Criteria:**
- [ ] Test creating chain from scratch
- [ ] Test loading and switching presets
- [ ] Test parameter editing
- [ ] Test saving preset
- [ ] Test drag-and-drop units
- [ ] Test keyboard shortcuts
- [ ] Test tab switching
- [ ] Test settings dialog
- [ ] Tests run stably and repeatably
- [ ] Timeout handling for waits

**Implementation Notes:**
- Use TestFX DSL for clarity
- Implement custom wait conditions
- Handle GTK event processing

**Related Issues:** AMP-4, AMP-7

---

### AMP-28: Performance Optimization - Audio Thread

**Type:** Story  
**Story Points:** 13  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Optimize audio thread for low latency and CPU efficiency.

**Acceptance Criteria:**
- [ ] Audio thread uses pre-allocated buffers
- [ ] No allocations in real-time path
- [ ] Lock-free communication with UI thread
- [ ] Parameter updates applied on block boundaries
- [ ] CPU load <5% for 10-unit chain at 44.1kHz/256 samples
- [ ] Latency <10ms for typical chain
- [ ] Profile with async-profiler confirms no hotspots
- [ ] Benchmark suite shows consistent performance

**Implementation Notes:**
- Use inline functions to reduce overhead
- Preallocate all data structures
- Measure with JMH benchmarks
- Profile real audio processing

**Related Issues:** AMP-6, AMP-26

---

### AMP-29: Performance Optimization - UI Rendering

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Optimize UI rendering for smooth 60fps performance with many units.

**Acceptance Criteria:**
- [ ] Canvas rendering at 60fps with 20+ units
- [ ] Smooth animations (no frame drops)
- [ ] CSS styling optimized (no expensive operations)
- [ ] Unit widget rendering cached where possible
- [ ] Metrics graphs render smoothly
- [ ] Profile shows no GPU bottlenecks
- [ ] Measure with GTK profiler

**Implementation Notes:**
- Use drawing caches for static elements
- Batch redraws
- Profile with GTK tools

**Related Issues:** AMP-17, AMP-4

---

### AMP-30: Documentation & User Guide

**Type:** Story  
**Story Points:** 5  
**Priority:** Medium  
**Assignee:** [TBD]  

**Description:**
Create user documentation, API documentation, and developer guide.

**Acceptance Criteria:**
- [ ] User guide (markdown): Installation, basic usage, preset management
- [ ] API documentation (KDoc) for public APIs
- [ ] Developer guide: Architecture overview, building, contributing
- [ ] Troubleshooting guide
- [ ] Video tutorials (optional)
- [ ] All inline code comments clear and helpful

**Implementation Notes:**
- Use KDoc for Kotlin docs
- Keep docs current with code
- Host on GitHub Pages or similar

**Related Issues:** All

---

### AMP-31: Error Recovery & Resilience

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Implement comprehensive error handling and recovery mechanisms.

**Acceptance Criteria:**
- [ ] Audio device disconnection handled gracefully
- [ ] Config file corruption doesn't crash app
- [ ] Invalid presets skip with warning
- [ ] DSP module crashes don't crash engine
- [ ] Out of memory handled cleanly
- [ ] Network-related errors (if any) handled
- [ ] Error recovery dialog offers helpful suggestions
- [ ] Tests verify error scenarios

**Implementation Notes:**
- Use sealed Result types
- Implement retry logic where appropriate
- Log detailed error information
- Show user-friendly error messages

**Related Issues:** AMP-6, AMP-3

---

### AMP-32: Release Preparation & CI/CD

**Type:** Story  
**Story Points:** 8  
**Priority:** High  
**Assignee:** [TBD]  

**Description:**
Prepare for release: version management, packaging, distribution setup.

**Acceptance Criteria:**
- [ ] Version bumping script (major.minor.patch)
- [ ] Changelog generation from commits
- [ ] GitHub Actions builds release artifacts
- [ ] Linux AppImage generation
- [ ] Debian package creation
- [ ] macOS DMG (if supported)
- [ ] Windows installer (if supported)
- [ ] Code signing (if applicable)
- [ ] Release notes template

**Implementation Notes:**
- Use semantic versioning
- Automate with GitHub Actions
- Create release artifacts for each commit
- Support multiple package formats

**Related Issues:** AMP-1

---

## Sprint 4+ Features (Future Enhancements)

**Goal:** Advanced features, ecosystem integration, platform expansion

### AMP-33: MIDI Learn & CC Control

**Type:** Story  
**Story Points:** 8  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Enable MIDI learn mode to map hardware MIDI controllers to parameters.

**Acceptance Criteria:**
- [ ] MIDI learn button in settings
- [ ] Click parameter, then MIDI control to map
- [ ] MIDI CC messages update parameters
- [ ] Mapping persistence
- [ ] Multiple controls can map to same parameter
- [ ] Feedback to controller (optional)

---

### AMP-34: Visualization & Spectrum Analyzer

**Type:** Story  
**Story Points:** 5  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Add real-time spectrum analyzer and waveform visualization.

**Acceptance Criteria:**
- [ ] FFT-based spectrum display
- [ ] Waveform scope view
- [ ] Toggle between views
- [ ] Smooth animations
- [ ] Low CPU impact

---

### AMP-35: Parallel Processing & Side Chains

**Type:** Story  
**Story Points:** 13  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Support parallel chains and sidechain routing (advanced feature).

**Acceptance Criteria:**
- [ ] Multiple parallel signal paths
- [ ] Sidechain routing UI
- [ ] Mix controls for parallel paths
- [ ] Complex routing visualization

---

### AMP-36: Preset Tagging & Smart Collections

**Type:** Story  
**Story Points:** 5  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Enhanced preset organization with smart collections and favorites.

**Acceptance Criteria:**
- [ ] Create custom collections
- [ ] Smart collections (search-based)
- [ ] Favorite presets with star rating
- [ ] Sort by rating, date, usage

---

### AMP-37: Macro Parameters & Expressions

**Type:** Story  
**Story Points:** 8  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Allow macro controls that modulate multiple parameters via expressions.

**Acceptance Criteria:**
- [ ] Define macro with expression (e.g., "drive * 2 + tone")
- [ ] Link multiple parameters to macro
- [ ] Macro slider controls all linked params
- [ ] Expression validation

---

### AMP-38: Cloud Sync & Sharing

**Type:** Story  
**Story Points:** 13  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Cloud storage and sharing of presets (GitHub Gists or custom backend).

**Acceptance Criteria:**
- [ ] Upload preset to cloud
- [ ] Share via link
- [ ] Download shared presets
- [ ] Version history

---

### AMP-39: Plugin System & SDK

**Type:** Story  
**Story Points:** 21  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Public SDK for third-party DSP plugin development.

**Acceptance Criteria:**
- [ ] Kotlin DSP plugin template
- [ ] Build system support
- [ ] Distribution mechanism
- [ ] Documentation and examples

---

### AMP-40: macOS/Windows Native Builds

**Type:** Story  
**Story Points:** 13  
**Priority:** Low  
**Status:** Backlog  

**Description:**
Cross-platform support for macOS and Windows.

**Acceptance Criteria:**
- [ ] Builds successfully on macOS
- [ ] Builds successfully on Windows
- [ ] Audio backend selection works
- [ ] Native installers for each platform
- [ ] Testing on CI/CD

---

## Dependency Map

```
MVP Phase:
  AMP-1 (Project Setup)
    ├── AMP-2 (Domain Models)
    ├── AMP-3 (Config Management)
    ├── AMP-4 (UI Framework)
    ├── AMP-5 (DSP Modules)
    ├── AMP-6 (Audio Engine)
    │   └── AMP-8 (EventBus)
    └── AMP-7 (Chain Editor)

Sprint 1:
  AMP-9 (Presets)
    ├── AMP-10 (Presets UI)
    └── AMP-16 (Chain Manager)
  AMP-11 (Library)
    ├── AMP-12 (Parameter Editor)
    ├── AMP-13 (Dashboard)
    ├── AMP-14 (Settings)
    └── AMP-15 (Shortcuts)

Sprint 2:
  AMP-17 (Styling)
  AMP-18 (LV2 Support)
  AMP-19 (Export/Import)
  AMP-20 (Copy/Paste)
  AMP-22 (Metrics)
  AMP-24 (Clipping Detection)

Sprint 3:
  AMP-25 (Unit Tests)
  AMP-26 (Integration Tests)
  AMP-27 (UI Tests)
  AMP-28 (Audio Optimization)
  AMP-29 (UI Optimization)
  AMP-30 (Docs)
  AMP-31 (Error Recovery)
  AMP-32 (Release)

Future:
  AMP-33+ (Nice-to-have features)
```

---

## Story Point Summary

| Phase | Count | Total Points |
|-------|-------|--------------|
| MVP | 8 | 58 |
| Sprint 1 | 8 | 56 |
| Sprint 2 | 8 | 51 |
| Sprint 3 | 8 | 62 |
| **Total** | **32** | **227** |

**Notes:**
- MVP estimated at ~2 weeks (3 points/day)
- Sprints estimated at 2 weeks each
- Estimate includes testing, documentation, and contingency
- Actual velocity will drive sprint scope adjustments

---

## Import to Jira

### Format for Bulk Create

Each ticket can be imported to Jira using the following format:

```
Summary: [Ticket Title]
Type: [Task/Story]
Story Points: [#]
Priority: [Critical/High/Medium/Low]
Description: [Description text]
Acceptance Criteria:
  [ ] Criterion 1
  [ ] Criterion 2
Related Issues: [AMP-X, AMP-Y]
Labels: [MVP/Sprint1/Sprint2/Sprint3/Future]
```

### Jira Project Setup Recommendations

1. **Board Type:** Kanban or Scrum
2. **Estimation:** Fibonacci (1, 2, 3, 5, 8, 13, 21)
3. **Custom Fields:**
   - Kotlin Coverage %
   - Audio Thread Safe (Yes/No)
   - Test Coverage %
4. **Labels:** MVP, Sprint1, Sprint2, Sprint3, Future, Audio, UI, Testing
5. **Epic Structure:** Organize by Sprint

---

## Timeline Estimate

| Phase | Duration | Start | End |
|-------|----------|-------|-----|
| **MVP** | 4 weeks | Week 1 | Week 4 |
| **Sprint 1** | 2 weeks | Week 5 | Week 8 |
| **Sprint 2** | 2 weeks | Week 9 | Week 12 |
| **Sprint 3** | 2 weeks | Week 13 | Week 16 |
| **Buffer** | 2 weeks | Week 17-18 | [Contingency] |
| **Release** | 2 weeks | Week 19-20 | Alpha/Beta |

**Total Estimated Timeline:** 20 weeks (5 months)

---

## Success Criteria for Each Phase

### MVP Success (End of Week 4)
- ✅ Core data models complete and serializable
- ✅ Audio engine processes through chain
- ✅ Basic UI with 5 tabs functional
- ✅ Chain Editor allows drag-drop reordering
- ✅ Can save/load presets
- ✅ All tests passing
- ✅ No major audio artifacts or crashes

### Sprint 1 Success (End of Week 8)
- ✅ Complete workflow: create → edit → save → load
- ✅ All 5 main UI sections fully functional
- ✅ Library browsable and functional
- ✅ Preset management complete
- ✅ Performance acceptable for 10+ unit chains
- ✅ Test coverage >70%
- ✅ Ready for early user testing

### Sprint 2 Success (End of Week 12)
- ✅ Skeumorphic UI looks professional
- ✅ Performance optimized (60fps, <5% CPU)
- ✅ Advanced features working (copy/paste, profiles)
- ✅ Error handling robust
- ✅ Clipping detection and feedback
- ✅ Comprehensive metrics display

### Sprint 3 Success (End of Week 16)
- ✅ Test coverage >80%
- ✅ All performance benchmarks met
- ✅ Documentation complete
- ✅ CI/CD pipeline working
- ✅ Packaging ready for multiple distros
- ✅ Release candidate quality
- ✅ Ready for alpha/beta release

---

## Risks & Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|-----------|
| GTK4 learning curve | High | Medium | Allocate spike time, consider UI expert |
| JACK integration complex | High | Medium | Early prototype, fallback to PulseAudio |
| DSP audio glitches | High | Medium | Thorough audio testing, defer LV2 if needed |
| Performance not achievable | High | Low | Benchmark early, profile continuously |
| Cross-platform GTK issues | Medium | Medium | Focus on Linux first, other platforms later |
| Scope creep | Medium | High | Strict sprint boundaries, defer to future |

---

## Notes for Development Team

1. **Start with MVP:** Focus intensely on core features first. Don't add polish or advanced features in MVP phase.

2. **Test Early:** Write tests as you code, not after. Aim for 80%+ coverage.

3. **Audio First:** Audio quality and stability are critical. Test with real audio equipment early.

4. **Performance Matters:** Real-time audio has hard constraints. Profile and optimize continuously.

5. **User Testing:** Get feedback from musicians/producers starting in Sprint 1.

6. **Documentation:** Keep architecture docs and inline comments current as code evolves.

7. **Kotlin Idioms:** Use Kotlin features (coroutines, extension functions, sealed classes) effectively.

8. **CI/CD:** Automate everything. Run tests on every commit.

---

**Document Version:** 1.0  
**Last Updated:** June 2026  
**Status:** Ready for Sprint Planning
