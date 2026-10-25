# Native OBJ Model Pipeline

Target: Minecraft 1.20.1 Fabric, MTR 4.0.5. The native library is built and tested
independently of Gradle and of Minecraft; nothing in this work required a running
client.

## What was replaced

MTR's optimized rendering path — the default for every vehicle — prepared its GPU
buffers entirely on the Java side:

```
ModelResourceLoader.loadModel
  OptimizedModel.ObjModel.loadModel
    ObjModelLoader.loadModel            de.javagl.obj parsing, group splitting
      ObjUtils.convertToRenderable      triangulate, make UVs and normals unique
      RawMesh                           one Vertex per renderable vertex
    RawMesh.applyRotation X 180         per mesh
    ObjModel.addTransformation          per vehicle part position: full copy
    RawModel.append                     merge by MaterialProperties
    RawModel.generateNormals            copies every vertex again
    RawModel.distinct                   HashMap<Vertex, Integer> per material
    RawModel.upload                     interleaved buffer on the Java heap
```

Every vertex of that becomes a `Vertex` holding two `Vector3f` wrappers, each wrapping
a JOML vector. `generateNormals` copies every vertex a second time, every placement
copies the whole mesh again, and the finished interleaved buffer is allocated on the
Java heap, filled, handed to `glBufferData`, and then becomes garbage.

The whole chain now runs in C++ (`native/`), producing the interleaved vertex buffer
and index buffer directly in native memory, which the Java side uploads by address.

## Numbers

`native/tests/wrobj_bench.exe` on Windows x64, MSVC 19.51, Release. Model is MTR's own
`assets/mtr/models/object/teapot.obj` — 540 KB of OBJ, 13,209 triangles, no normals
(so every normal is generated), no MTL.

| Placements | Vertices | Triangles | Vertex buffer | Total | Parse | Normals + distinct + pack |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 105,684 | 50,560 | 2.4 MiB | 81 ms | 25 ms | 57 ms |
| 40 | 528,000 | 252,800 | 12.1 MiB | 370 ms | 28 ms | 342 ms |

Throughput is 1.3–1.4 M vertices per second in both cases.

The Java heap the reference pipeline would have allocated for the same work is
reported by the library as `estimatedJavaHeapBytes`: **11 MiB** for the 8-placement
case and **59 MiB** for the 40-placement one. That figure counts one `Vertex` plus a
pair of `Vector3f` per final vertex, one `Face` plus its `int[]` per triangle, and a
map entry per de-duplication probe. It is an estimate from allocation counts, not a
measurement, and it is the number to watch when judging whether a change helped.

What the table shows that matters: parsing is a fixed cost (25 ms → 28 ms across a
fivefold increase in placements) while the geometry phase grows with placements. A
resource pack pays the parse once per model and then a per-placement cost that is a
straight memory walk rather than an object graph.

## Not measured here

- **No in-game FPS or frame-time measurement.** The draw path is untouched, so the
  win is in resource reload and model preparation rather than in steady-state frames.
- **No Java-versus-native timing on identical work.** The benchmark times the native
  path alone. Timing both would need the Gradle test environment.
- **Draw submission is unchanged.** `BatchManager` still makes one draw call per vertex
  array and applies six global vertex attributes per array.
- **Peak memory during a build is not obviously lower.** Both pipelines hold the source
  meshes and the accumulated model at once. What goes away is the garbage, not the
  working set.

## Correctness

The claim is byte-for-byte equality of the GPU buffers, and it is supported at four
levels:

| Level | Where | Coverage |
| --- | --- | --- |
| Unit tests | `native/tests/wrobj_tests.cpp` | 143 assertions over the parser, transforms, de-duplication, normals, texture normalisation, material handling and lifetime |
| JNI smoke test | `fabric/src/test/.../NativeObjSmokeTest.java` | 31 assertions that the bridge loads, round-trips a model and keeps buckets isolated |
| Vertex layout | `fabric/src/test/.../NativeVertexLayoutCompatibilityTest.java` | reads `OptimizedModel`'s static initialiser with ASM and asserts the declared layout is the 24-byte layout the native packer writes |
| Golden equivalence | `fabric/src/test/.../NativeObjEquivalenceTest.java` | runs the real `ObjReader`/`ObjSplitting`/`ObjUtils`/`RawMesh`/`RawModel` code and the native path over the same ten cases, comparing buffers byte for byte |

The layout check is the one that matters most, because its failure mode is silent: a
mismatch would make the GPU read position bytes as UV and normals out of the padding,
which renders as garbage rather than raising an error. It runs with no GL context and was
confirmed to pass against the bundled backend jar.

Three findings from building this are worth recording, because in each case reading the
source gave the wrong answer and only running the JVM settled it:

1. **MTR's colour packing is not ARGB.** `mergeColor(r, g, b, a) = r << 24 | g << 16 |
   b << 8 | a` puts red in the most significant byte and alpha in the least
   significant, so opaque red is `0xFF0000FF`. `native/tools/ColorReference.java` runs
   the expressions on the JDK, and those outputs are the test expectations.
2. **Every channel truncates.** `(int) (0.85F * 255.0F)` is 216, not 217.
3. **A doubled backslash in a texture path collapses to one slash.**
   `texture.replace("\\\\", "/").replace("\\", "/")` replaces the two-character sequence
   with a single character, which a naive single-pass replacement does not reproduce. The
   first version of the native normaliser emitted two slashes, which would have changed
   the resolved path and therefore split materials the Java pipeline merges into separate
   buckets — a rendering bug, not a cosmetic one.
   `native/tools/TexturePathReference.java` prints the JVM's answers, and eleven of them
   are now test expectations.

Two of those three were bugs in the first draft of this port, found by building reference
programs rather than by re-reading the code. That is the argument for the reference
programs existing at all.

## Constraints of this environment

- The Gradle build could not complete here: `meta.fabricmc.net`, Maven Central and the
  Forge userdev repository were intermittently unreachable, and `fabric/build.gradle`
  needs the Fabric build metadata at configuration time. The mod-side compile and the
  golden equivalence test therefore run in CI (`.github/workflows/native.yml` and the
  existing build workflows) rather than locally.
- Everything that could be verified without that build was verified: the native library
  builds clean with `/W4`, 143 native assertions pass, the JNI bridge loads and passes 31
  assertions, the ASM-based layout check passes against the bundled backend jar, all
  eleven integration source files type-check against that jar, and all three JVM-side test
  sources compile against it.
- What remains unverified here is the golden equivalence run itself — the one check that
  executes the real `RawMesh`/`RawModel` code. It is written, compiled and wired into
  `:fabric:test`; it needs a machine that can reach the dependency repositories.

## Scope

The rewrite covers model loading and buffer preparation: OBJ and MTL parsing, group
splitting, the renderable conversion, every model and placement transform, normal
generation, vertex de-duplication, and the interleaved buffer packing.

The per-frame draw path was inspected and deliberately left alone. `BatchManager` groups
by `MaterialProperties` and then issues one draw call per `VertexArray`, applying six
global vertex attributes per array; that is a handful of GL calls per bucket per frame,
against tens of thousands of vertex operations per model load. The measured cost is not
there, and changing it would mean owning shader and render-layer behaviour that only a
live client can validate. Anyone reading this work as "the OBJ renderer is now native"
should read it as "the OBJ model pipeline is now native".

