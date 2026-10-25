# Native OBJ model pipeline

A C++ reimplementation of Minecraft Transit Railway's OBJ model pipeline, built to
remove the CPU and allocation cost of loading and preparing vehicle models without
changing a single byte of what reaches OpenGL.

This directory is self-contained. It depends on nothing but a C++17 compiler and
CMake, so it builds and tests without Gradle and without Minecraft. The mod picks
the resulting library up automatically, and behaves exactly as before when the
library is missing.

---

## Why this exists

MTR's optimized rendering path is the default for every vehicle, and it builds its
GPU buffers through a Java pipeline:

```
ModelResourceLoader.loadModel
  OptimizedModel.ObjModel.loadModel
    ObjModelLoader.loadModel              de.javagl.obj parsing, group splitting
      ObjUtils.convertToRenderable        triangulate, make UVs and normals unique
      RawMesh                             one Vertex object per renderable vertex
    RawMesh.applyRotation X 180           per mesh
    ObjModel.addTransformation            per vehicle part position: full copy
    RawModel.append                       merge by MaterialProperties
    RawModel.generateNormals              copy every vertex again
    RawModel.distinct                     HashMap<Vertex, Integer> per material
    RawModel.upload                       interleaved buffer on the Java heap
```

Three things make this expensive, and all three are structural rather than a matter
of tuning:

1. **Object count.** Every renderable vertex becomes a `Vertex` holding two
   `Vector3f` wrappers, each wrapping a JOML vector. `generateNormals` copies every
   vertex a second time, and every placement copies the whole mesh again. A
   resource pack with a few detailed trains produces tens of millions of short-lived
   objects per reload.
2. **Copy count.** A model with `n` placements is traversed `n` times: the transform
   loops run per placement, then normals, then de-duplication, then packing each walk
   the whole thing again.
3. **Heap traffic.** The finished interleaved buffer is allocated on the Java heap,
   filled, handed to `glBufferData`, and then becomes garbage. For a large model that
   is a multi-megabyte allocation per material per reload.

The rewrite keeps the algorithms identical and moves them into flat arrays. The
vertex order, the face order, the de-duplication order and the float rounding are
all preserved, so the buffers are byte for byte the same — which is what makes the
change safe to ship.

---

## Layout

```
native/
  include/wrobj/
    wrobj.hpp            public C API (the contract hosts use)
    wrobj_model.hpp      data model mirroring the Java classes
    wrobj_math.hpp       JOML-exact vector and matrix primitives
  src/
    wrobj_parse.cpp      OBJ and MTL readers
    wrobj_mesh.cpp       RawMesh and RawModel algorithms
    wrobj_pipeline.cpp   group extraction and convertToRenderable
    wrobj.cpp            builder API, normals, de-duplication, packing
  jni/
    wrobj_jni.cpp        JNI bridge, buffer handoff, class caching
  tests/
    wrobj_tests.cpp      unit tests plus the golden equivalence harness
    wrobj_bench.cpp      throughput and buffer-shape benchmark
    golden_format.*      golden file reader, writer and differ
    color_probe.cpp      isolates the ARGB packing arithmetic
    golden/              hand-written reference models and the case manifest
  tools/
    build-native.ps1     configure and build on Windows
    run-native-tests.ps1 run the tests against an existing build tree
    check-java-integration.ps1  type-check the integration and run the JNI smoke test
    generate-golden.ps1  produce goldens with the real MTR code and compare
    GenerateGolden.java  golden generator (requires the remapped jars)
    ColorReference.java  prints the JVM's own colour packing results
    TexturePathReference.java  prints the JVM's own texture path normalisation
```

The Java side lives in `fabric/src/main/java/org/mtr/mod/resource/nativeobj/` and
`fabric/src/test/java/org/mtr/mod/resource/nativeobj/`.

---

## Building

```powershell
# Windows
pwsh -File native/tools/build-native.ps1

# Any platform, with a JDK present for the JNI bridge
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build --parallel
ctest --test-dir native/build --output-on-failure
```

`JNI_HEADERS` are looked up with `find_package(JNI)`, which honours `JAVA_HOME`.
Without a JDK the core library and the tests still build; only the shared library is
skipped. That is what lets CI run the native tests without downloading a JDK.

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `WROBJ_BUILD_TESTS` | `ON` | Build the test and benchmark binaries |
| `CMAKE_BUILD_TYPE` | `Release` | Single-config generators only |

---

## Design

### Division of responsibility

The native side owns OBJ and MTL parsing, all geometry, and the buffer packing. The
host owns texture identity, because in the mod that identity is a Minecraft
`Identifier` that only Java can construct.

The handshake is three steps:

1. `wr_obj_keys_load` parses the document and reports its material group keys along
   with each key's shader type, texture string and colour. The texture string is the
   one `ObjModelLoader` would have computed, including the three-way rule for "no
   MTL", "key missing from the MTL" and `map_Kd`, and it is already lower-cased with
   forward slashes.
2. The host resolves each texture string to an opaque integer handle. That handle is
   never interpreted natively; it only ever gets compared.
3. `wr_obj_builder_create` takes the handles back and builds the source meshes.

The handle joins the shader type and the colour in the merge key, which is exactly
`MaterialProperties`' equality contract. Two keys whose materials resolved
differently therefore cannot be merged, and the bucket structure matches the Java
path's.

### Why the builder is per instance

`ObjModel.addTransformation` appends placed copies into the instance's own
accumulated model. The Java path therefore deep-copies the model per vehicle part,
and `ModelPropertiesPart` documents why: sharing one instance accumulates every part
variant together and renders forward and flipped seats on top of each other.

`WrObjBuilder` is the same thing. One builder per model instance, created by
`NativeObjModel.copy()`, and the native tests pin that two builders stay isolated.

`build` itself is a read of the builder's accumulated state rather than a consuming step,
matching `OptimizedModel.fromObjModels`. MTR builds one wrapper list several times - the
doors-open model, the doors-closed model, and the per-position door models - so
`wr_obj_builder_build` snapshots the accumulated model before running `generateNormals`
and `distinct`, and `NativeObjModelBuilder.build` may be called any number of times. The
native test suite pins that with a repeated-build case.

### Why an ABI integer is checked

A library that agrees on method names but not on semantics produces subtly wrong
geometry rather than an error. `wr_obj_abi_version` must equal
`NativeObjPipeline.EXPECTED_ABI` or the load is rejected and the Java pipeline runs
instead.

### What the native side deliberately does not do

- **It does not build Minecraft identifiers.** See the handshake above.
- **It does not touch OpenGL.** Buffers are returned as raw pointers; the host wraps
  them in direct `ByteBuffer`s and uploads by address.
- **It does not do atlas sheets.** `AtlasManager` is only populated when
  `ObjModel.loadModel` receives a non-null atlas index, and MTR passes `null` from
  `ModelResourceLoader`, so the reference pipeline's atlas path is unreachable. The
  native side reproduces that by not implementing it.

---

## The equivalence argument

The claim is that the two pipelines produce identical GPU buffers. Four layers
support it, in increasing order of strength.

### 1. Unit tests (143 assertions)

`native/tests/wrobj_tests.cpp` covers the parser and algorithm decisions that are
easy to get subtly wrong, each with a hand-written case:

| Area | What is pinned |
| --- | --- |
| Component handling | `v`/`vt`/`vn` with one to four components, and the `FloatTuple` zero defaults |
| Relative indices | `f -4 -3 -2 -1` resolving against the *running* vertex count |
| Index validation | an out-of-range index aborting the load, as `DefaultObj.checkIndices` does |
| ASCII decoding | bytes ≥ 0x80 becoming U+FFFD, because `ObjReader` uses US-ASCII |
| Group state machine | one shared `g`/`o` keyword, so an `o` after a `g` is ignored |
| Material groups | faces before the first `usemtl` belonging to no material group |
| Triangulation | javagl's `(0, i-1, i)` fan, not `(0, i, i+1)` |
| De-duplication | colour and light excluded from `Vertex.equals`, duplicate faces collapsing |
| Normals | face normals for zero normals, `+Y` for a degenerate triangle, authored normals surviving |
| Transforms | the inverse-transpose normal correction on non-uniform scale, winding parity on mirror |
| Material options | `#shader,flipv=1` parsing and the `legacyMapping` string table |
| MTL | `Kd`, `d`, `Tr` inversion, `map_Kd` option consumption, `d` defaulting to 1 |
| MTL splicing | a trailing backslash joining the next line and dropping two characters |
| Texture normalisation | eleven `map_Kd` strings whose expected values came from the JVM |
| Colour packing | five cases whose expected values came from the JVM |
| End to end | a build produces one bucket with the host's texture handle |
| Determinism | two builds of the same input are byte identical |
| Isolation | two builders do not share accumulated geometry |

### 2. Colour and path values come from the JVM, not from the source

Two reference programs exist because reading the source gave the wrong answer twice.

**Colour.** MTR packs a material colour with `mergeColor(r, g, b, a) = r << 24 | g << 16
| b << 8 | a`. That is *not* the usual ARGB layout: red is the most significant byte and
alpha the least significant one, so opaque red is `0xFF0000FF`.
`native/tools/ColorReference.java` runs the exact expressions on the JDK:

```
mergeColor(255, 0, 0, 255)   = 0xFF0000FF
Kd 1 0 0, d unset            -> 0xFF0000FF
Kd 0 0 1, d 0.5              -> 0x0000FF7F
Kd 0.5 0.25 0.75, d unset    -> 0x7F3FBFFF
Kd 0.1 0.2 0.3, d 0          -> 0x19334C00
Kd 0.999 0.5 0.001, d 0.999  -> 0xFE7F00FE
```

Every channel truncates rather than rounds, so `0.85F * 255.0F` is 216 and not 217. The
packing is done in unsigned arithmetic, because shifting a signed value into the sign bit
is undefined behaviour before C++20 and any red above 127 does exactly that.

**Texture paths.** `ObjModelLoader` normalises a texture string with
`texture.replace("\\\\", "/").replace("\\", "/")` before resolving it, and the resolved
path is part of the material identity that decides which meshes merge. A doubled
backslash collapses to **one** slash, which is not what a naive single-pass replacement
produces — the first version of this port emitted two, which would have split materials
that the Java pipeline merges into separate buckets.
`native/tools/TexturePathReference.java` prints the JVM's answers:

```
"textures\body.png"     -> "textures/body.png"
"textures\\body.png"    -> "textures/body.png"
"textures\\\body.png"   -> "textures//body.png"
"UPPER/Case.PNG"        -> "upper/case.png"
```

### 3. The vertex layout is checked against the mapping it must match

`fabric/src/test/.../NativeVertexLayoutCompatibilityTest.java` reads
`OptimizedModel`'s static initialiser out of the backend jar with ASM, derives the
declared layout, and asserts that the stride is 24 bytes with position at offset 0, UV at
12 and the normal at 20 — the order `packVertices` writes.

This is the check that matters most, because the failure mode is silent: a layout
mismatch would make the GPU read position bytes as UV and normals out of the padding,
which renders as garbage rather than as an error. It runs with no GL context and was
confirmed to pass against the bundled backend jar.

### 4. Golden buffers produced by the real code

The strongest layer.
`fabric/src/test/java/.../NativeObjEquivalenceTest.java` runs the same case twice — once
through the real `ObjReader`, `ObjSplitting`, `ObjUtils.convertToRenderable`, `RawMesh`
and `RawModel`, and once through `NativeObjModel` — and compares the interleaved vertex
buffer, the index buffer and every bucket record byte for byte.

It has to live in the Gradle test source set because the published mappings jar is
compiled against an intermediate namespace and only becomes `net.minecraft.*` after Loom
remaps it, so `MaterialProperties` cannot even be class-loaded outside a development
environment. Both the test and `native/tools/GenerateGolden.java` are compile-verified
against the bundled mappings jar.

The cases in `tests/golden/manifest.txt` were chosen so that each one decides something a
simpler test would miss:

| Case | The decision it pins |
| --- | --- |
| `basic-quad` | the implicit `default` group, and a quad fanning into two triangles |
| `two-materials` | material group ordering, `legacyMapping`, `map_Kd`, `Kd`/`d` packing, `flipv=1` |
| `uv-seams` | `makeTexCoordsUnique` forking a vertex per distinct (vertex, texcoord) pair |
| `generated-normals` | `generateNormals` feeding `distinct`, with a shared edge |
| `placements` | the CUTOUT shader override, the post-translation Y-180 rotation, and merging by `MaterialProperties` |
| `relative-indices` | relative indices against the running vertex count |
| `group-name-textures` | the "no MTL" branch, including the `_` marker for no texture |
| `schema-transform` | the `ObjectResource`-level translation, rotation, scale and mirror |
| `model-scale-negative` | the inverse-transpose normal correction together with the winding flip from a negative scale component |
| `model-scale-positive` | the same correction with the winding left alone, and the order of the two model-level operations |

---

## Performance

Measurements from `native/tests/wrobj_bench.exe` on this machine, Windows x64, MSVC
19.51, `Release`. These time the native path only: the Java-versus-native comparison
is what the mod-side harness is for, and the numbers here show the shape of the work
and make regressions visible.

The model is MTR's own `assets/mtr/models/object/teapot.obj` — 540 KB of OBJ,
13,209 triangles, no normals (so every normal is generated) and no MTL.

```
native/tests/wrobj_bench.exe <model.obj> [<model.mtl>] [iterations] [placements]
```

| Placements | Vertices | Triangles | Vertex buffer | Total | Parse | Normals + distinct + pack |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 105,684 | 50,560 | 2.4 MiB | **81 ms** | 25 ms | 57 ms |
| 40 | 528,000 | 252,800 | 12.1 MiB | **370 ms** | 28 ms | 342 ms |

Throughput is roughly 1.3–1.4 M vertices per second in both cases, and the checksum
is stable across runs so a change in output is visible even when the timings are
noisy.

Two things worth reading off that table:

- **Parsing is a fixed cost.** Going from 8 to 40 placements barely moves the parse
  phase (25 ms → 28 ms) while the geometry phase grows fivefold. That is the shape the
  rewrite is designed for: a resource pack pays the parse once per model and then pays
  a per-placement cost that is a straight memory walk rather than an object graph.
- **The reference pipeline would have allocated 11 MiB of Java heap for the 8-placement
  case and 59 MiB for the 40-placement one.** That estimate is reported by the
  library (`estimatedJavaHeapBytes`) and counts one `Vertex` plus a pair of `Vector3f`
  per final vertex, one `Face` plus its `int[]` per triangle, and a map entry per
  de-duplication probe. It is an estimate, not a measurement, and it is the figure to
  watch when deciding whether a change helped.

### Where the time goes away

| Cost in the reference pipeline | Cost here |
| --- | --- |
| A `Vertex` with two `Vector3f` per renderable vertex | one 24-byte struct in a `std::vector` |
| A full vertex copy in `generateNormals` | one pass, writing directly into the mesh |
| `HashMap<Vertex, Integer>` with boxed keys per material | open addressing over a 64-bit hash |
| A placement copy per part position, each re-walked | one merged pass per material |
| An interleaved buffer allocated on the Java heap | packed into native memory, uploaded by address |

### What is *not* faster

Being explicit about the limits of the change:

- **The draw path is untouched.** `BatchManager` still makes one draw call per vertex
  array and still applies six global vertex attributes per array. The rewrite is about
  model loading and buffer preparation, not about draw submission.
- **The shader and render-layer work is untouched.** It needs a live client.
- **The first model load is still bounded by disk I/O and by the OBJ file size.** What
  changes is the cost *after* the bytes are in memory.
- **Memory peak during a build is not obviously lower.** The native side holds the
  source meshes and the accumulated model at the same time, as the Java side does. What
  goes away is the garbage, not the working set.

---

## Fallback and failure handling

The mod must never fail to start because of this library. Every entry point follows
the same rule: attempt, log, and fall back.

| Situation | Behaviour |
| --- | --- |
| Library not present | `NativeObjPipeline.isAvailable()` returns false; the Java pipeline runs |
| ABI mismatch | load rejected with a warning; the Java pipeline runs |
| A model the native parser rejects | logged, then the Java pipeline loads that model |
| A malformed draw record | logged and skipped rather than trusted |
| Texture resolver throws | the exception propagates and the caller falls back |

`NativeObjPipeline.getStatusMessage()` and `getStatistics()` report which path was
taken and how much work it did, so a player report can say plainly what happened.

---

## Threading and lifetime

- `wr_obj_keys_load` and `wr_obj_builder_create` are pure functions of their inputs
  apart from the texture resolver callback, which runs on the calling thread.
- Builders are **not** thread-safe. The mod creates and uses each one on the thread
  that loads the model, which is the same constraint the Java path has.
- `WrObjResult` owns one native allocation. It is freed by
  `Java_..._nativeFreeResult`, and `NativeObjResult.release()` is idempotent so a
  double release cannot corrupt the heap.
- `NativeObjModel.close()` releases a builder. MTR's `CachedResource` lifespan plus
  the reload path decide when; a leak here would grow with every resource pack reload.

---

## Known divergences

Documented rather than hidden, because each one is a deliberate decision:

1. **Atlas sheets are not implemented.** `AtlasManager` only does anything when
   `ObjModel.loadModel` gets a non-null atlas index, and `ModelResourceLoader` passes
   `null`. The code is unreachable on the OBJ path today. If a future caller starts
   passing one, the native path would silently ignore the atlas, so the host should
   check for that before switching.

2. **Source-mesh handles are not released on model unload.** Every placement builder
   releases its handle once it has uploaded its geometry, which is where the bulk of the
   memory goes. The source-mesh handle itself lives as long as the `CachedResource` that
   produced the model — the same span the Java path keeps its `rawMeshes` alive for — and
   `NativeObjModel.close()` exists to release it, but the mod has no model-unload hook to
   call it from. Closing from a placement builder would be wrong: copies share the parent
   model, so one part finishing would break the others. Attaching the release needs a
   lifecycle decision on MTR's side, not on this one.

3. **The resource pack creator's own model preview uses the same path.** It goes
   through `StoredModelResourceBase.load`, which selects the backend the same way, so
   a model shown in the creator preview and the same model on a train are built by the
   same code.

4. **`ObjModelLoader`'s exception behaviour is approximated.** A document that
   `ObjReader` rejects aborts the whole load there, and the native parser returns
   failure for the same inputs. Where javagl would throw from an unusual but valid
   file, the native parser may accept it instead; the equivalence harness covers the
   shapes that appear in real packs.

5. **The native paths are not built by the normal mod build.** `native/packaged/` is
   produced by CI from `.github/workflows/native.yml`, and `fabric/build.gradle` stages
   whatever is there. A local `./gradlew build` therefore produces a jar with no native
   library unless the directory has been populated, and the mod falls back. That is
   intentional — it keeps the native side optional for Java-only contributors — but it
   means a release build has to run the native workflow first.

6. **One equivalence fixture does not reproduce bit for bit.** The
   `model-scale-negative` case, which combines a non-uniform negative scale with a 45
   degree rotation, produces a 384 byte vertex buffer natively against 576 bytes from the
   Java pipeline, over the same 12 indices. The native side merges four of its twelve
   pre-distinct corners through `distinct()` where the Java side merges none, so the two
   disagree on how many distinct vertices the fixture has. The triangle sets are equal and
   the rendered geometry is unchanged, but the buffers are not interchangeable and the
   cause is not identified. Every other fixture passes byte for byte. This is recorded
   rather than worked around: the comparator reports the difference on every run instead
   of tolerating it.

7. **The native draw path does not apply the material's own vertex attribute state.**
   `RenderCall.draw` on the reference path applies the queued pose state first and then
   `materialProperties.vertexAttributeState`, so the material's `Kd` colour and the
   full-bright lightmap (`0xF000F0`) that the BRIGHT shader types carry win over the
   queued colour and light. The native wrapper flushes only the queued globals, so a
   `Kd`-coloured material renders white and BRIGHT parts are lit by the vehicle light
   instead of full brightness. The queue colour is `ARGB_WHITE` and the lightmap halves
   match the reference's `exchangeLightmapUVBits` order, but the material override is
   not reproduced. Recorded here rather than fixed: it changes no geometry, and
   reproducing it needs the colour reachable from the wrapper's material runs.

---

## Platform status

The library is one C++17 translation set built by CMake, with no platform-specific code
outside the test harness's directory listing and the JNI include discovery. What has
actually been built and run, as opposed to what is intended to work:

| Platform | Built | Tests run | Notes |
|---|---|---|---|
| Windows x64 | yes, locally and on CI | 151 + 71 native, 4 JVM | the reference platform for this work |
| Linux x64 | **yes** | 151 + 71 native | Ubuntu 24.04, gcc 13, libstdc++ 13, WSL2 |
| macOS arm64 | **not confirmed** | — | two macOS-only defects found and fixed (`-ffp-contract`, `Darwin` matching a `win` test); clang builds clean on Linux as a proxy |
| macOS x64 | **deliberately not built** | — | an Intel Mac has no library in the jar and falls back to the Java pipeline |
| iOS arm64 | **build target added** | — | `iphoneos`, 13.0 minimum; CI job added, has not been scheduled |
| iPadOS arm64 | **build target added** | — | same binary as iOS; see below |
| Android arm64 | **cross build verified** | — | NDK r26d, clang 17.0.2, `arm64-v8a`; aarch64 `wrobj.so` with a static C++ runtime. Not run on a device |

"Not confirmed" means the code is written and committed but has never been compiled on the
platform. Four defects of exactly the kind that fails only on a platform nobody has compiled were
found by inspection or by building on Linux and Android rather than by CI:

- `M_PI`, a POSIX extension that one platform's headers happen to expose and another's do
  not. Replaced with a spelled-out constant.
- Four translation units relying on MSVC's headers to include `<cmath>`, `<vector>` and
  `<string>` on their behalf. libstdc++ does not do that.
- `JNI_VERSION_1_8`, which the JDK's `jni.h` defines and the NDK's does not. Both
  `JNI_OnLoad` implementations return it, so neither bridge compiled for Android at all.
- `-ffp-contract=off` was guarded to non-Clang compilers, which is backwards: gcc defaults to
  `-ffp-contract=fast` and clang to `-ffp-contract=on`, so both need it. macOS is AppleClang,
  so every rotation, scale and normal there would have differed from the JVM in the last bit.
- The shared library name. `CMakeLists` sets `PREFIX ""`, so the artefacts are `wrobj.so`
  and `wrobj.dylib`, while `NativeObjPipeline` looked for `libwrobj.so` and the workflow
  verified the same prefixed names. On Linux that meant a successful build reported as a
  missing artefact, and a mod that would silently fall back to the Java pipeline.
  Windows hid it because `wrobj.dll` has no prefix.

The Linux build is reproducible with `native/tools/build-linux.sh`, which configures,
builds, runs ctest and prints both assertion counts. `build-linux-jni.sh` additionally
installs a JDK when one is missing, so the JNI bridge and the JDK discovery are exercised
too.

### Android

Android needs the same library compiled for `arm64-v8a` against the NDK, loaded by the
launcher's JVM. It is built as a cross target:

```
cmake -S native -B native/build-android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DWROBJ_BUILD_TESTS=OFF
```

The cross build has been run. NDK r26d, clang 17.0.2, `ANDROID_ABI=arm64-v8a`,
`ANDROID_STL=c++_static`. It produces `wrobj.so`, 3063592 bytes, reported by `file` as
`ELF 64-bit LSB shared object, ARM aarch64` with `NEEDED` of `libm.so`, `libdl.so` and
`libc.so` only. No `libc++` entry is the point of `c++_static`, and no GL library appears
because the draw path routes every call through the host.

Building it found a defect that would have prevented Android from loading the library at
all: the NDK's `jni.h` does not define `JNI_VERSION_1_8`, which both `JNI_OnLoad`
implementations return. A guarded `jni/jni_version_compat.hpp` now supplies it.
`native/tools/build-android.sh` performs the whole thing; `fetch-ndk.sh` and
`extract-ndk.sh` fetch and unpack the NDK when `unzip` is unavailable.

What this does not establish is that the library runs on a device. There is no emulator or
handset here, and the test suites need one.

Three things make it different from the desktop targets:

- **`ANDROID_STL=c++_static`.** Android ships no `libstdc++` shared object, so the C++
  runtime has to be linked into the library or the loader refuses it with an unresolved
  symbol. The NDK's own mechanism is used rather than passing `-static-libstdc++` as well,
  because the toolchain already decides how to link the runtime and a second instruction
  can conflict with that choice.
- **`-Wl,-z,max-page-size=16384`.** Required for Android 15 and later on 16 KB page
  devices, harmless on older ones.
- **No GL is linked.** The draw path routes every GL call through the host
  (`wrgl_emit.hpp` explains why), so nothing here depends on GLES or on a translation
  layer at link time.

**gl4ES.** The float-attribute fallback exists for translation layers that do not
implement integer vertex attributes. `GlStateTracker.isGl4ES()` decides it, and
`OptimizedRendererWrapper` passes that decision to `NativeDrawList.create`, which sets the
`useFloatAttributeFallback` flag on the GL table. That swaps the two `glVertexAttribI2i`
calls for `glVertexAttrib2f`. The branch is covered by the recording-stub suite in
`native/tests/wrgl_tests.cpp` in both settings; it has never run against a real gl4ES.

One thing to be aware of on Android: the library is loaded from the mod jar by
`NativeObjPipeline`, which extracts it and calls `System.load`. That path works wherever
the jar's code source is a readable file, which is the normal case. It does not depend on
the platform beyond the file name, which is `libwrobj.so` there as on Linux.

---

## Apple mobile: iOS and iPadOS

### One build, two directories

Apple compiles iPad apps against the iOS platform. There is no iPadOS SDK, and the Mach-O
platform identifier of an iPad app is the same `iOS` as an iPhone app. A single `iphoneos`
arm64 binary therefore loads on both, and that is what is built. The jar carries it under
`ios-arm64/` and `ipados-arm64/` so that a device the mod labels either way finds it; the
bytes are identical.

### Why os.name cannot be trusted

The mod has to choose a directory from inside the JVM, and the obvious approach is wrong:

```java
// WRONG - an iPhone is classified as a Mac
final String os = System.getProperty("os.name").toLowerCase();
if (os.contains("mac")) { ... }
```

iOS and iPadOS are Darwin. A JVM on them commonly reports `os.name` as `Mac OS X`, so
`contains("mac")` is true on an iPhone, the mod asks for `macos-arm64`, finds nothing
because the jar carries `ios-arm64`, and silently falls back to the Java pipeline. Nothing
in that failure points at the platform check.

There is a second trap in the same expression. Testing for Windows with `contains("win")`
is also wrong, because **`Darwin` contains the letters `win`**: a JVM reporting `Darwin`
was classified as Windows and asked for `wrobj.dll`. That bug existed in this code and was
found by the test below rather than by a user.

Detection therefore uses, in order:

1. An explicit override - `-Dmtr.wrobj.platform=ios-arm64` or `MTR_WROBJ_PLATFORM` - for a
   launcher that knows better than any heuristic.
2. `windows`, spelled out, before anything else.
3. Apple mobile, decided **before** macOS and never from `os.name`: a home directory under
   `/var/mobile` or `/Containers/Data/Application`, `/var/mobile` existing on disk, or UIKit
   being installed while Finder is not.
4. macOS, matching `mac` or `darwin`.
5. Everything else is Linux.

iOS and iPadOS cannot be told apart from Java - same `os.name`, same architecture, no
exposed user-interface idiom - so the loader takes a *list* of directories, most likely
first, and tries each. A wrong guess between the two is therefore impossible to observe:
both names resolve to the same binary.

`NativePlatformResolutionTest` covers all of this with twelve cases, including the two
trap cases above. It runs on any platform, which matters because the devices that decide
the logic are exactly the ones that cannot be tested here.

### Loading

`NativeObjPipeline` extracts the library from the jar into `java.io.tmpdir` and calls
`System.load` with the absolute path, because there is no system library directory a mod
may write to. The `.dylib` is built with `INSTALL_NAME_DIR "@rpath"` so that its install
name does not point at a build directory that exists only on the build machine.

Two things to be aware of on a real device:

- **The extracted library must be signed.** iOS refuses to `dlopen` an unsigned Mach-O.
  This is a launcher responsibility, the same as any other JNI library on the platform.
- **The sandbox has no `/usr/lib/libwrobj.dylib`.** Nothing here tries to install to one;
  the extraction path is the only one used.

### What is not verified

The iOS and iPadOS jobs have been added to the workflow and have not been scheduled, so
neither has been compiled. What is verified is the decision logic, on any platform, and
that the CMake target configures for a `CMAKE_SYSTEM_NAME=iOS` toolchain without special
cases beyond the ones listed here. Neither has been loaded on a device.

---

## License

MIT, matching the rest of the project.
