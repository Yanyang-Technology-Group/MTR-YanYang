# YanYang Optimisation Port — 4.0.5 → 4.1.0-beta.2

This branch (`1.21.1`) ports the YanYang Technology Group performance work from the
MTR 4.0.5 codebase (MC 1.20.1, `master` branch of this repository) to the upstream
`stonecutter` codebase (MTR 4.1.0-beta.2, MC 1.21.1 / 1.21.4, Fabric and NeoForge),
fixes the AI passenger feature, and fixes additional bugs discovered during the port.

Every commit in this branch is self-contained and describes its changes in detail; this
document gives the overview, the porting decisions and the known follow-ups.

## Ported optimisations

| Area | Change | Upstream problem |
| --- | --- | --- |
| Client data | `MinecraftClientData.sync()` split into a full sync (static data: rail graph, wrappers, spatial index) and `syncDynamic()` (vehicles, lifts, passengers) | Dynamic data packets arrive ~once per second and rebuilt the whole rail index every time |
| Client data | `railWrapperList` is a hash map; `checkAndRemoveFromMap` uses a hash set + `removeIf`; rail wrappers are immutable and only recreated when the rail instance changes | Array-map lookups made the wrapper rebuild O(rails²); streams + tree sets + intermediate lists allocated per sync |
| Client data | `getLift()` resolves through `liftIdMap` (kept fresh in `syncDynamic`) | Linear scan over all lifts per call, including per-tick while riding a lift |
| Spatial queries | `ClientSpatialIndex` — grid index over stations and platforms with LRU query caches, backing `MTRClient.findStation` / `findClosePlatform` | Every route map, PIDS, sign and sensor block entity scanned all stations/platforms every frame |
| AI passengers | Five bug fixes plus a render cache — see below | — |
| Arrivals | `ArrivalsCache` reverse index (platform id → response positions) + selection LRU | Each PIDS filtered the whole arrival cache per frame |
| Block entities | `BlockEntityRenderCulling` — per-frame frustum captured at the world render event (Fabric `WorldRenderEvents`, NeoForge `RenderLevelStageEvent`), cleared at the end of level rendering; PSD tops, APG glass and doors test an inflated box against it | `shouldRenderOffScreen` = true rendered every such block entity every frame regardless of view direction |
| Route maps | `RouteMapSpanCache` — per-direction suffix distances over a row of route-map blocks, shared per renderer per frame | `getTextureNumber` walked the row block by block per block entity per frame (O(N²) block queries per row) |
| Doors | All door textures pre-resolved as statics | 2–4 `String.format` + `ResourceLocation.parse` per door per frame |
| Render queue | `MainRenderer` queues are linked hash maps; the pending/current double-buffer swaps references; drained callback lists are pooled | Array-map `computeIfAbsent` was O(n) per scheduled render (O(n²) per layer per frame); the frame swap copied every map; callback lists were re-allocated per texture per layer per frame |
| Rails | `RailGeometryCache` — geometry samples cached per (rail, interval, offsets), replayed instead of re-walking the curve | Every visible rail re-walked its curve every frame, allocating several vectors per segment |
| Rails | Signal colour lookups no longer eagerly allocate the `getOrDefault` default | Fresh `LongArrayList` per rail per frame even on a hit |
| Occlusion | `BoundedOcclusionCullingInstance` + `CachedCullingDataProvider` + mutable block position | Unbounded voxel loops on large boxes (single queries could run for seconds); one block position allocated per voxel; block opacity re-read per voxel |
| Resources | `getRails()` / `getObjects()` / `getLifts()` return immutable snapshots swapped on reload | Every call copied the whole list (per frame for the lift renderer) |
| Lifts | Lift track paths memoised per floor pair with a short TTL | Recursive world scan per lift per frame, including idle lifts |
| Textures | `RouteMapGenerator.setConstants()` runs on the worker thread before generation | Constants were set on the caller thread after scheduling — a race |
| Server | Parallel path computation — see below | — |
| Server | `PathDataIdMixin` caches path hex ids | Six-part `String.format` recomputed per query while vehicles simulate |

### AI passenger fixes

1. **Idle facing**: the turn interpolation multiplied the raw millisecond progress
   (clamped to 1000) by the angular difference — overshooting the target heading by up
   to three orders of magnitude. The factor is now normalised over a one-second turn.
2. **Walking home was invisible**: the last walking leg has no end platform; the
   destination was only looked up in the landmark map, so home-bound passengers
   (endLandmarkId == 0) never rendered. It now falls back to the passenger's home,
   mirroring the start-position lookup.
3. **Waiting passengers walked the whole inter-station leg**: passengers on a vehicle
   leg that had not yet boarded rendered interpolating from the boarding platform to
   the destination platform — walking across the world until the train arrived. They
   now stand at a deterministic position on the boarding platform.
4. **Clock skew**: negative leg progress extrapolated passengers behind the leg start;
   progress is now clamped to zero and the required-speed division guards against
   non-positive durations.
5. **Per-frame entity allocation**: `RemotePlayer` + `GameProfile` + `UUID` + `Random`
   were constructed for every passenger every frame in both the walking and on-board
   renderers. `PassengerRenderCache` now caches entities per passenger (invalidated on
   level change) and the deterministic on-board standing positions per floor layout.

### Parallel path computation

The core's `SidingPathFinder.findPathTick` advances its A* searches on the server
thread (up to 5 ms per tick per batch) when threaded simulation is off. The port
vendored the dependency-free compute module from the YanYang Transport-Simulation-Core
fork (`org.mtr.core.path.compute`, no external dependencies) and wires it in through:

- `DataPathEpochMixin` — bumps the snapshot epoch whenever `Data.sync()` rebuilds the
  rail graph, so stale results can never be committed;
- `SidingPathFinderMixin` — routes `findPathTick` through `ServerRailPaths.process`,
  which snapshots the graph into immutable arrays, runs searches on a small worker
  pool, coalesces identical requests, and commits results with a 5 ms per-tick budget
  without ever blocking on an incomplete result;
- lifecycle hooks in `MTR.java` (start/stop on server lifecycle, begin/end tick around
  `manualTick()`).

Airplane mode and graphs over the snapshot limits (32,768 nodes / 262,144 edges) fall
back to the serial search permanently per network. System properties: `-Dmtr.path.parallel=false`
to disable, `-Dmtr.path.workers=N` to override the worker count, `-Dmtr.path.metrics=true`
for periodic summaries.

## Additional upstream bugs fixed during the port

- **Client paths resolved against an empty data instance** (`PacketUpdateDynamicData` /
  `PacketUpdateData`): every vehicle's path was resolved against a freshly allocated,
  empty `MinecraftClientData`, so every path segment's rail lookup always missed and
  was overwritten with a synthesized fallback rail — discarding real signal colours and
  speed limits and allocating heavily. Paths are now resolved against the real client
  data, and only for (re)created vehicles.
- **`GenericCacheBase` expiry**: the periodic cleanup collected the key currently being
  queried instead of the expired entries' keys — expired entries were never evicted
  (leak) and freshly queried keys were dropped. Expiry now removes entries by their own
  timeout.
- **`HIDDEN_PLAYERS`** is a hash set; the riding-interpolation cleanup uses `contains`
  instead of a nested stream.

## Not ported (and why)

- **Iris render-order / feedback-arc-set caching** (YanYang commits `dd51ae10`,
  `f7237716`): Iris 1.8.x for 1.21.1 moved the batched entity rendering classes
  (`net.irisshaders.iris.batchedentityrendering.*` →
  `net.irisshaders.batchedentityrendering.impl.*`) and replaced the bundled ithaka
  digraph library with a fastutil/`OptionalInt`-based fork, so the FAS mixins
  (`IndexedFeedbackGraph`, `ArrayFeedbackArcSet`, `FeedbackGraphTraversal`,
  `IrisFeedbackArcSetMixin`, `IrisFeedbackTraversalMixin`) need rewriting against a
  different API. Recommendation: record a JFR profile on 1.21.1 + Iris 1.8 first to
  confirm the sort is still hot, then port; the `RenderOrderCache` /
  `GraphResultCache` scaffolding can then be reused almost as-is.
- **`ModelLift1` caching in `LiftWrapper`**: upstream commented out the entire lift
  car model render and the class no longer exists in the 4.1.0 tree (the
  4.0.5 model is written against the removed `org.mtr.mapping` abstraction layer).
  The lift car model needs migrating to the new model system first; once restored, add
  the YanYang per-wrapper model caching (cache keyed by rounded
  height/width/depth/double-sided).
- **`OcclusionSafepointMixin`** (safepoint polls inside the occlusion-culling loops):
  the upstream `WorkerThread` was rewritten; the remaining occlusion work is bounded
  by `BoundedOcclusionCullingInstance`, which addresses the same stall at the source.
  Re-evaluate if long culling scans reappear in safepoint logs.
- **Optimised-renderer mixins** (`MaterialPropertiesMixin`, `VertexAttributeStateMixin`,
  `OptimizedBatchManagerMixin`, `OptimizedShaderManagerMixin`, `OptimizedRendererWrapper`,
  `ModelPropertiesPart` skip): the `org.mtr.mapping.render` optimised renderer backend
  was removed in 4.1.0 in favour of `NewOptimizedModel` — no equivalent hot path
  remains.
- **`MagicRailTiltRegistryMixin`**: targets the MAGIC addon on 1.20.1; re-add with a
  `@Pseudo` mixin if/when MAGIC is available on 1.21.1.
- **`CachedResource` changes**: superseded by the rewritten `GenericCacheBase` (plus the
  expiry bug fix above).

## Building

```shell
./gradlew setupFiles          # generate translations, schema classes, webserver files
./gradlew buildAndCollect     # build every version target
```

Notes for this environment/branch:

- GitHub Packages dependencies (`org.mtr:transport-simulation-core`,
  `org.mtr:transport-simulation-core-build-tools`) require a GitHub token
  (`gpr.user` / `gpr.key` gradle properties or `GITHUB_TOKEN`), even for anonymous
  reads — plan credentials before building from scratch.
- The Stonecutter and Fletching Table plugin versions are pinned; the previous `+`
  floating versions now resolve to releases requiring Gradle 9.7+ (the wrapper ships
  9.5.1).
- The vendored `org.mtr.core.path.compute` module intentionally duplicates the YanYang
  Transport-Simulation-Core fork's `path-computation` module so this repository builds
  without a sibling checkout. If that module evolves, sync the five files under
  `src/main/java/org/mtr/core/path/compute/`.
