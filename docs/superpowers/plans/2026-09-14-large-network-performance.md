# Large Network Performance Implementation

The user approved the preceding analysis and requested implementation. The
follow-up work is performed entirely by the primary agent; delegation is forbidden.

## Objective and Compatibility

The user confirmed Minecraft 1.20.1 Fabric as the only build target during
implementation. Verification and artifacts are scoped to that combination.

Reduce repeated client work in large stations and railway networks while
preserving vehicle motion, boarding, signals, arrivals, resource reloads, and
the existing Fabric mapping boundary. The checked-in TSC binary is the
compatibility target for MTR. The adjacent newer TSC checkout has different
APIs and must not silently replace that binary.

## Implementation Order

- [x] Establish the baseline Fabric build and test command with JDK 21.
- [x] Separate dynamic vehicle/lift synchronization from static graph rebuilds.
  Update only changed vehicle path caches; use a hash map for rail wrappers.
  Verify vehicle removal, persistent state cleanup, lift replacement, and that
  dynamic updates preserve static rail adjacency objects.
- [x] Index station/platform spatial queries and cache static rail sampling.
  Invalidate on static sync; preserve overlapping station selection, negative
  coordinates, long rails, and rail edits. Compare queries with brute-force
  fixtures and compare cached samples with direct RailMath output.
- [x] Index arrival data and share selected-platform results between displays.
  Preserve server ordering and caller ownership, invalidate on new responses,
  and retain request subscription behavior. Test interleaved platforms,
  empty results, changed responses, and caller mutations.
- [x] Repair resource cache ownership and renewal. Expired nested caches and
  replaced resource packs must be collectible. Separate cache-hit renewal from
  load admission and preserve recursive loading behavior. Test expiry,
  admission, nested loads, and resource collection.
- [x] Fix backend reload/transparent queue behavior through the supported
  mapping integration after confirming how the packaged backend is built.
  Avoid binary-only edits or unverified OpenGL thread migration.
- [x] Run focused tests, the complete available test suite, package Fabric
  1.20.1, review the diff, and synchronize verified source
  changes back to the user's original checkout.

## Broader Rendering Work

GPU instancing, model LOD assets, fixed-frequency simulation, and TSC signal
component caching are separate architecture changes. Their integration needs
the matching Minecraft-Mappings source/build and representative in-game
fixtures. Report actual completed work and any remaining scope explicitly;
unit tests alone do not establish an FPS or GC-pause improvement.

## Measurements

Use first-entry, stationary hub, travel/return after cache expiry, resource
reload, and vehicle update bursts. Record p99/p99.9 frame duration, allocation
rate, retained heap, draw calls, and simulation tick time. Distinguish nearby
scene density from remote network size. Runtime gameplay verification is only
claimed when exercised in a real client.

## Build and Verification

Build target: Minecraft 1.20.1 Fabric, MTR 4.0.5, bundled TSC API.
Use JDK 21 to run Gradle; the mod's Java toolchain remains Java 17.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --configure-on-demand :fabric:setupFiles :fabric:test :fabric:build
```

Fabric unit tests and a real client smoke check were run. The client check
confirmed shader object reuse between model loads, replacement after shader
invalidation, and clearing of hidden translucent batches. The test client exited
normally. The smoke helper was temporary and is not included in the release JAR.

This change does not establish an FPS improvement: a representative dense world
and the user's custom model pack are still needed for frame-time measurements.
No TSC dependency binary was replaced. Forge is outside the requested scope.

## Dense Hub Follow-Up

A 40-second JFR recording of the user's running Fabric 1.20.1 client on
2026-09-14 collected 2,195 render-thread samples. Iris 1.7.6 render-order
resolution appeared in 718 samples, platform lookup in 316 (including callers),
and resource-cache ticking in 122. Twelve GCs paused for 35.913 ms total, with
a longest pause of 3.539 ms. Sampling percentages are not frame-time measurements.
Iris entity batching remains active with shader packs disabled.

- [x] Exercise more than 1,024 distinct station queries per frame, prevent
  cache thrashing with a bounded 32,768-entry platform cache, preserve invalidation.
- [x] Register only loaded resource caches for expiry; remove expired entries,
  retain weak ownership and the existing per-tick recursive loading policy.
- [x] Reuse Iris ordering only for identical graph snapshots, including vertex
  identity, iteration order, edge weights and transparency categories. Hook the
  buffer's sort-and-reset boundary, preserving the original algorithm on misses.
  Bound snapshot storage and return caller-owned lists. Iris remains optional.
- [x] Run regression tests, client smoke with/without Iris, build the Fabric
  release and synchronize changes to the original checkout. Keep the user's
  running game intact; frame-time comparison requires loading the new build.

## Iris Ordering Follow-Up

- [x] Reproduce one-entry thrashing with alternating world/minimap inputs.
- [x] Retain multiple exact graph snapshots within fixed LRU/record limits.
- [x] Cache unchanged SCC feedback-edge results while preserving Iris's solver
  and checking parent weights. Restrict the hook to the current Iris call.
- [x] Test changed inputs, hash collisions, memory limits, nested scopes and
  exception cleanup; compare output with Iris in an isolated real client.
- [x] Run all 44 tests, the Fabric release build, and isolated clients with and
  without Iris. The third-build station FPS still requires in-game measurement.

The failed live diagnostic probe and the switch to isolated clients are recorded
in `docs/performance/2026-09-14-dense-hub.md`.

## Station Rendering Follow-Up

- [x] Record performance3 in the original view and the heavier scene selected
  by the user, using only standard JFR recording.
- [x] Cull off-screen built-in station doors and route maps with expanded bounds;
  retain animation updates and bypass unknown/shadow rendering contexts.
- [x] Replace linear material grouping and full queue copies while preserving
  ordering, cancellation and next-frame submissions.
- [x] Reuse per-lift procedural models across frames/network updates and rebuild
  when geometry changes. Precompute finite door texture identifiers.
- [x] Run all 49 tests and the Fabric build; validate Fabric event integration,
  cached/fresh lift vertex equivalence, and clients with/without Iris.

The 60 FPS target still needs measurement in the same heavy scene using the
fourth build. The original Iris solver remains a fallback for changing groups.

## Fifth Build: Outside Station Ordering

- [x] Verify performance4 is installed and collect a 45-second JFR profile in
  the user's outside heavy-load view. The platform-level result is now 60+ FPS.
- [x] Reproduce repeated material hashing on changing cycle components.
- [x] Relabel supported component misses with integer vertices while retaining
  the original Iris solver, iteration order, weights and fallback behavior.
- [x] Compare ordered feedback graphs and randomized complete render orders
  against the actual Iris 1.7.6 implementation in an isolated Java 17 client.
- [x] Complete all 54 Fabric tests, the release build and no-Iris validation.
- [x] Package performance5 for a same-scene FPS measurement after restart.

## Sixth Build: Traversal and Platform Scans

- [x] Verify the installed performance5 artifact and record another 45-second
  outside-station profile. Check line-level Iris stacks and the MTR worker.
- [x] Reuse adjacency snapshots and iterative DFS inside the existing solver;
  retain candidate enumeration, scoring, tie-breaking and original fallbacks.
- [x] Replace repeated route-map span walks with frame-local suffix results.
- [x] Test the occlusion-cache hypothesis against the library; discard the
  redundant cache and reuse only the worker's coordinate object/native queries.
- [x] Pass all 61 tests, the Fabric build and the Iris client order oracle.
- [x] Complete no-Iris validation and build performance6.

## Seventh Build: Measured Allocation Hotspots

- [x] Update both origins to the confirmed YanYang forks and inspect the two
  allocation commits without merging them into the performance work.
- [x] Record performance6 with built-in JFR and measure allocation using thread
  counter deltas, separately from sampled weights and CPU samples.
- [x] Cache immutable PathData direction IDs using the existing formatter on
  misses; recycle cleared callback arrays under explicit capacity limits.
- [x] Pass the full Fabric suite and the 1.20.1 release build (65 tests).
- [x] Verify woven PathData output and allocated bytes in Java 17 clients
  with and without Iris, then package performance7.
