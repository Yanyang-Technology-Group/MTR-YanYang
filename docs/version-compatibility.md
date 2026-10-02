# Minecraft version compatibility

This branch's published optimization baseline is Fabric 1.20.1. Build configuration changes alone do not establish compatibility with a new Minecraft release.

| Target | Required Java | Mapping layer | Status |
| --- | --- | --- | --- |
| 1.20.4 | 17 | Included in `libs/` | Build and regression tests passed; in-game validation pending |
| 1.21.1 | 21 | Not included; upstream currently stops at 1.20.4 | Not supported yet |
| 1.21.4 | 21 | Not included; upstream currently stops at 1.20.4 | Not supported yet |

## Building Fabric 1.20.4

Run Gradle with JDK 21, with a Java 17 toolchain installed locally. Missing toolchains are not downloaded automatically. Check out `Transport-Simulation-Core` next to this repository as described in the README, or pass `-PpathComputationDir=<path-to-path-computation>`.

```sh
./gradlew :fabric:setupFiles :fabric:test :fabric:build -PmodLoader=fabric -PminecraftVersion=1.20.4
```

On PowerShell, quote the entire property argument:

```powershell
.\gradlew.bat :fabric:setupFiles :fabric:test :fabric:build '-PmodLoader=fabric' '-PminecraftVersion=1.20.4'
```

`modLoader` accepts `fabric`, `forge`, or `all` (the default). Selecting Fabric avoids configuring Forge and downloading its unrelated build dependencies. CI checks out the pinned TSC path-computation dependency inside the workspace.

## Remaining 1.21 migration work

The Java toolchain selector now recognizes the Java 21 requirement from 1.20.5 onward. Loot-table generation selects the singular `loot_table` resource directory from 1.21 onward. These are preparatory changes, not a working 1.21 port.

Before enabling either 1.21 target:

1. Port the version-specific `org.mtr.mapping.holder` and `org.mtr.mapping.mapper` implementations, including changes to networking, item data, registry access, GUI and rendering APIs.
2. Update the common mapping library's access-widener and mixin generators. The bundled generators contain version-specific code only through 1.20.4; copying or renaming an old mapping jar is not sufficient.
3. Select matching Fabric API, Yarn, Mod Menu, Jade, WTHIT and Iris dependencies, and verify optional Iris/MAGIC mixins against their actual target class layouts.
4. Run unit tests and launch both client and dedicated server on each target version; verify rendering, world save/load, packet synchronization and parallel path computation.

Forge/NeoForge support is a separate port: some optimization sources currently call Fabric rendering events and Yarn-named Minecraft classes directly. This document does not claim Forge compatibility.
