# MTR-YanYang

[中文](#中文) | [English](#english)

## 中文

MTR-YanYang 是 [Minecraft Transit Railway（MTR）](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) 的社区优化分支，基于 MTR 4.0.5。性能优化基线为 **Minecraft 1.20.1 Fabric**；**Fabric 1.20.4 已通过源码构建和回归测试**，尚待游戏内验证。1.21.1、1.21.4 的映射层迁移尚未完成。

本分支重点改善密集车站、多站台、多轨道和多列车场景中的渲染开销、临时内存分配，以及车辆和电梯更新中的重复工作。MTR 4.0 的基础架构来自上游；以下列出本分支提交的优化。

### 已落地的优化

| 方向 | 改动 |
| --- | --- |
| 密集场景渲染 | 客户端空间索引、轨道几何缓存、线路图跨度缓存和渲染回调复用，减少重复扫描、计算与分配。 |
| Iris 渲染排序 | 缓存排序结果，以整数索引和数组遍历减少图搜索中的临时集合，保留原有候选搜索和排序规则。 |
| 内存分配 | 缓存路径 ID，复用模型矩阵上传缓冲区，减少顶点属性和材质哈希中的临时数组；对可选 MAGIC 集成使用有界轨道 ID 缓存。 |
| 动态贴图响应 | 将动态贴图任务与轨道遮挡检测解耦，避免站名、线路图等内容长时间等待遮挡任务。 |
| 遮挡检测 | 限制超大包围盒扫描，优先处理当前可见性请求，并在每轮检测中缓存重复的方块不透明度查询。 |
| 到站信息与同步 | 优化到站信息缓存及车辆、电梯更新处理，减少重复工作。 |
| 服务端轨道信号 | 复用信号预留状态的四个缓冲集合，按精确颜色键判断变化，减少每 tick 的集合复制与临时分配。 |
| 服务端轨道同步 | 用轨道空间索引和玩家可见性缓存减少全路网与玩家的重复扫描，保留每 tick 的客户端更新与原有模拟顺序。 |
| 车辆占用查询 | 将占用轨道端点对映射为整数 ID，用 primitive 数组记录区间并复用前后 tick 缓冲，保留实时写读、方向与自身车辆排除规则。 |
| 信号闭塞区 | 缓存同色轨道的连通成员，替代每次预留的重复递归；拓扑或颜色变化后重算，通行检查仍读取四个实时预留窗口。 |
| OBJ 模型渲染（C++ 原生） | 将 OBJ/MTL 解析、分组拆分、逐顶点可渲染化、模型与摆位变换、法线生成、顶点去重以及交错顶点/索引缓冲打包整条 CPU 链路改由 C++ 实现，缓冲直接在原生内存中生成并按地址上传；与原 Java 管线逐字节等价，缺少原生库时自动回退。详见 [native/README.md](native/README.md)。 |

### OBJ 模型渲染的 C++ 原生实现

MTR 的优化渲染路径（列车默认渲染路径）原先在 Java 侧完成全部模型预处理：每个可渲染顶点生成一个 `Vertex` 对象和两个 `Vector3f` 包装对象，`generateNormals` 会再整体复制一遍顶点，每个车辆部件摆位又会复制一次网格，去重依赖以装箱对象为键的 `HashMap`，最终交错顶点缓冲还要在 Java 堆上分配后再交给 `glBufferData`。大型追加包一次重载会产生数千万个短命对象。

本分支将这条链路整体改写为 C++（`native/`），保留原有算法、顶点顺序、面顺序、去重顺序与浮点舍入，因此**显存中的缓冲区与 Java 实现逐字节一致**，可在 `native/tests` 的纯原生金标准比对和 `fabric/src/test` 的 JVM 侧等价性测试中验证。以本仓库自带的 `teapot.obj`（540 KB、13,209 三角形）为例，8 次摆位约 81 ms、40 次摆位约 370 ms，吞吐约 1.3–1.4 M 顶点/秒；同规模下原 Java 管线预计需要分配约 11 MiB / 59 MiB 的堆内存。

原生库完全可选：未构建、ABI 不匹配、或某个模型无法解析时，均记录日志并回退到原有 Java 管线，不会导致游戏无法启动。

实际收益取决于线路规模、模型复杂度、硬件和模组组合，不承诺固定 FPS 或 TPS 增幅。

### 下载与安装

从 [Releases](https://github.com/Yanyang-Technology-Group/MTR-YanYang/releases) 下载对应 Minecraft 版本的 `YYMTR-fabric-4.0.5+<minecraft-version>-performance12.jar`，安装 Fabric Loader 和 Fabric API 后，将 jar 放入 `mods` 文件夹并重启游戏或服务器。替换已有 MTR 时只保留一个 MTR 运行 jar，不要安装 sources jar。

仓库最新代码可能包含尚未进入 Release 的改动，请以对应 Release 的说明为准。

### 源码构建

1.20.4 构建方式及 1.21.1 / 1.21.4 尚待完成的映射层迁移见 [版本兼容说明](docs/version-compatibility.md)。目前不能将修改版本号等同于完成 1.21 兼容。

构建工具需要 JDK 21；Fabric 1.20.1 和 1.20.4 使用 Java 17 工具链，请同时安装两个 JDK。Minecraft 1.21.x 要求 Java 21，但本分支尚不支持其映射层。还需满足项目现有 MTR 映射库及构建依赖。

游戏内"资源包创建器"页面来自 `website/` 下的 Angular 应用，其构建产物（`website/dist/website/browser/`）会在 `:fabric:setupFiles` 时被嵌入生成的 `WebserverResources` 类。**构建模组前必须先完成网站构建**：若 `website/dist/website/browser/index.html` 不存在，`setupFiles` 会自动执行 `npm install` 与 `npm run build`（需要安装 Node.js），构建失败时会给出明确提示；也可以手动执行 `./gradlew :fabric:buildWebsite` 或在 `website/` 目录自行 `npm install && npm run build`。若构建出的模组缺少该网站资源，游戏内打开"资源包创建器"将无法显示创建器（会显示明确错误页，而不是跳转到线路图页面）。

在 `Minecraft-Transit-Railway` 目录执行（`setupFiles` 会按需自动构建 `website/`，也可先手动执行 `./gradlew :fabric:buildWebsite`）：

```sh
./gradlew :fabric:setupFiles :fabric:test :fabric:build -PmodLoader=fabric -PminecraftVersion=1.20.4
```

产物位于 `build/release/`，命名为 `YYMTR-<loader>-4.0.5+<minecraft-version>-performance12.jar`；服务端精简包会追加 `-server`。

当前服务端轨道优化通过 Mixin 接入 `libs/Transport-Simulation-Core-0.0.1.jar`，不依赖相邻目录中的新版 TSC。玩家移动、同步半径、连接身份或轨道拓扑变化时会更新可见性缓存；超大范围走原 `Rail.closeTo` 精确判断。没有新增工作线程，车辆模拟、信号预留与网络发送仍按原顺序执行。

车辆占用的轨道 ID 和区间缓冲按时间层独立管理，拓扑失效在缓冲退休时清理，保留当前仍有效的占用。其他模组通过旧 Map 接口访问某个缓冲时，该缓冲会切换到真实 AVL Map 兼容表示，保留映射视图和后续读写行为。信号连通成员在首次使用时构建并复用；缓存使用弱成员引用，不跨 tick 缓存车辆占用或预留结果。

轨道替换后，旧路径仍可能持有单向连接的旧轨道。信号缓存只在具有相同可达范围的强连通成员之间共享；可达范围相对过大时复用原遍历，避免缓存空间随旧引用数量成倍增长。

除普通 `:fabric:test` 外，可在隔离的 Fabric 服务端进程中验证旧 core 的实际 Mixin、信号窗口轮转、玩家轨道同步，以及原生车辆占用写入与阻塞查询。测试还会独立加载原始 core JAR，对照信号预留、人工闭塞和车辆区间行为（成功时输出 `MTR_SERVER_OPTIMIZATION_INTEGRATION_OK` 后退出，不创建游戏世界）：

```sh
./gradlew :fabric:runServerOptimizationTest -PmodLoader=fabric -PminecraftVersion=1.20.1 -PserverOptimizationTest
```

增加 `-PcomputeBenchmark` 可在同一隔离进程中运行车辆与信号的合成基准，交替测量原 AVL/递归路径与优化路径的耗时、分配量；这些结果不代表实际服务器 TPS。

原生 OBJ 管线独立于 Gradle 构建与测试，只需要 C++17 编译器和 CMake；缺少它们时模组仍可正常构建，只是不带原生库：

```sh
# Linux / macOS
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build --parallel
ctest --test-dir native/build --output-on-failure

# Windows
pwsh -File native/tools/build-native.ps1
```

`find_package(JNI)` 会遵循 `JAVA_HOME`；没有 JDK 时只跳过 JNI 共享库，核心库与测试仍可构建运行。发布包中的原生库来自 `.github/workflows/native.yml` 的四平台产物，放在 `native/packaged/<os>-<arch>/` 后由 `:fabric:processResources` 打进 jar。不放置该目录时构建出的 jar 不含原生库，模组会自动回退到 Java 管线。

不依赖 Gradle 的快速检查：

```sh
# 用开发版映射 jar 对原生接入层做类型检查；加 -RunSmokeTest 会真正加载 DLL 跑一轮
pwsh -File native/tools/check-java-integration.ps1 -RunSmokeTest
```

`fabric/src/test` 中的等价性测试需要 Loom 重映射后的完整 Minecraft 类路径，因此只能在 Gradle 测试环境中运行：

```sh
./gradlew :fabric:test --tests '*NativeObj*' -PmodLoader=fabric -PminecraftVersion=1.20.1
```

### 优化提交索引

- [`dd51ae1`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/dd51ae1)：密集路网渲染、缓存与分配优化。
- [`29cc313`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/29cc313)：动态贴图与轨道遮挡任务解耦。
- [`445978e`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/445978e)：限制遮挡扫描并优先处理当前请求。
- [`f723771`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/f723771)：Iris 排序、模型绘制分配及查询缓存优化。
- [`f22d8ff`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/f22d8ff)：历史上的服务端并行寻路集成，已从当前代码移除。
- TSC [`3ab979e`](https://github.com/Yanyang-Technology-Group/Transport-Simulation-Core/commit/3ab979e)：历史上的独立批量并行路径计算模块；当前构建不再依赖它。

上述寻路集成已于 2026-10-15 移除，原因是实际使用中的体感表现为负优化。相关提交仅作历史记录，不代表当前版本支持该功能。

### 许可证与致谢

本项目采用 MIT 许可证，随附的 Noto 字体采用 SIL Open Font License。感谢上游 MTR、TSC 及相关依赖项目的开发者。本仓库为社区分支，并非上游官方发行版。

## English

MTR-YanYang is a community fork of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), based on MTR 4.0.5. The performance baseline is **Minecraft 1.20.1 Fabric**. **Fabric 1.20.4 has passed source builds and regression tests**, with in-game validation still pending. The 1.21.1 and 1.21.4 mapping ports are not complete.

The fork focuses on dense stations, platforms, tracks and trains. MTR 4.0's underlying architecture comes from upstream; this fork adds:

- Spatial indexing, rail geometry and route-map span caches, and reusable render callbacks.
- Iris render-order caching and array-based graph traversal that preserves the original candidate search and ordering rules.
- Path ID caching, reusable matrix upload buffers, and fewer temporary arrays in render-state hashing; bounded rail ID caching for the optional MAGIC integration.
- Independent scheduling for dynamic textures so station names and route maps do not wait behind rail occlusion work.
- Bounded occlusion scans, prioritization of current visibility requests, and per-pass opacity lookup caching.
- Improvements to arrival information caching and vehicle/lift update handling.
- Server-side signal reservation buffer reuse and cached rail/client visibility, preserving the original tick and notification order.
- Compact vehicle occupancy intervals with reusable tick windows, plus cached same-color signal components with live reservation checks.

### Installation

Download the matching Minecraft version's `YYMTR-fabric-4.0.5+<minecraft-version>-performance12.jar` from [Releases](https://github.com/Yanyang-Technology-Group/MTR-YanYang/releases), install Fabric Loader and Fabric API, place the jar in `mods`, and restart. Keep only one MTR runtime jar when replacing an existing installation. Do not install the sources jar. The latest repository commits may not yet be included in a published release.

### Building

Install JDK 21 for build tooling and JDK 17 for Fabric 1.20.1/1.20.4, plus the project's existing MTR mapping and build dependencies. Minecraft 1.21.x requires Java 21, but this branch's mapping layer does not support it yet. See the [compatibility notes](docs/version-compatibility.md).

Run `./gradlew :fabric:setupFiles :fabric:test :fabric:build -PmodLoader=fabric -PminecraftVersion=1.20.4` from the MTR repository. Release artifacts are written to `build/release/` using the `YYMTR-<loader>-4.0.5+<minecraft-version>-performance12.jar` naming convention; server-only artifacts append `-server`. The commit index above links to the optimization history in both repositories. The historical server-side parallel path integration was removed on 2026-10-15 because it felt slower in practice; its commits do not describe a feature supported by the current source.

### License and acknowledgements

This project is licensed under MIT. Bundled Noto fonts use the SIL Open Font License. Thanks to the upstream MTR and TSC developers and dependency maintainers. This is a community fork, not an official upstream release.
