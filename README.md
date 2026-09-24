# MTR-YanYang

[中文](#中文) | [English](#english)

## 中文

MTR-YanYang 是 [Minecraft Transit Railway（MTR）](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) 的社区优化分支，基于 MTR 4.0.5，当前优化与验证目标为 **Minecraft 1.20.1 Fabric**。

本分支重点改善密集车站、多站台、多轨道和多列车场景中的渲染开销、临时内存分配及服务端寻路计算。MTR 4.0 的基础架构来自上游；以下列出本分支提交的优化。

### 已落地的优化

| 方向 | 改动 |
| --- | --- |
| 密集场景渲染 | 客户端空间索引、轨道几何缓存、线路图跨度缓存和渲染回调复用，减少重复扫描、计算与分配。 |
| Iris 渲染排序 | 缓存排序结果，以整数索引和数组遍历减少图搜索中的临时集合，保留原有候选搜索和排序规则。 |
| 内存分配 | 缓存路径 ID，复用模型矩阵上传缓冲区，减少顶点属性和材质哈希中的临时数组；对可选 MAGIC 集成使用有界轨道 ID 缓存。 |
| 动态贴图响应 | 将动态贴图任务与轨道遮挡检测解耦，避免站名、线路图等内容长时间等待遮挡任务。 |
| 遮挡检测 | 限制超大包围盒扫描，优先处理当前可见性请求，并在每轮检测中缓存重复的方块不透明度查询。 |
| 到站信息与同步 | 优化到站信息缓存及车辆、电梯更新处理，减少重复工作。 |
| 服务端多核寻路 | 接入 TSC 独立计算模块，使用只读轨道图快照、有界线程池、请求合并与批处理；按原请求顺序在服务器线程组装和提交结果。 |

并行寻路具有版本失效机制，可在拓扑更新后淘汰旧结果；不支持的输入会回退到原串行路径。此项优化针对寻路计算，**不代表全部车辆模拟或 Minecraft 世界更新已多线程化**。

实际收益取决于线路规模、模型复杂度、硬件和模组组合，不承诺固定 FPS 或 TPS 增幅。

### 下载与安装

从 [Releases](https://github.com/Yanyang-Technology-Group/MTR-YanYang/releases) 下载适用于 **Fabric 1.20.1** 的运行 jar，安装 Fabric Loader 和 Fabric API 后，将 jar 放入 `mods` 文件夹并重启游戏或服务器。替换已有 MTR 时只保留一个 MTR 运行 jar，不要安装 sources jar。

仓库最新代码可能包含尚未进入 Release 的改动，请以对应 Release 的说明为准。

### 服务端寻路配置

以下为 JVM 启动参数，放在 `-jar` 前面；修改后需重启服务器。

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-Dmtr.path.parallel=true` | `true` | 启用并行寻路；设为 `false` 使用原串行实现。 |
| `-Dmtr.path.workers=8` | 自动选择 | 默认最多 8 个工作线程，受 JVM 可用处理器数减 2 的限制；手动配置最高 12 个。 |
| `-Dmtr.path.metrics=true` | `false` | 启用寻路统计，约每 30 秒汇总一次。 |

JVM 可用处理器数不超过 2，或启用了已有的 threaded simulation 模式时，本并行寻路集成不启用。任务队列容量为 32，每批最多 8 个请求。线程数应结合实际负载调整，更多线程不一定更快。

### 源码构建

构建需同时检出 [TSC 二改仓库](https://github.com/Yanyang-Technology-Group/Transport-Simulation-Core)，并保留以下相邻目录结构：

```text
MTR/
├── Minecraft-Transit-Railway/
└── Transport-Simulation-Core/
    └── path-computation/
```

MTR 通过 Gradle composite build 引用 `path-computation`，只引入该独立 Java 17 计算模块。构建工具需要 JDK 21，并需要 Java 17 工具链用于 Fabric 1.20.1 目标；还需满足项目现有 MTR 映射库及构建依赖。

在 `Minecraft-Transit-Railway` 目录执行：

```sh
./gradlew :fabric:test :fabric:build
```

产物位于 `build/release/`。

### 优化提交索引

- [`dd51ae1`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/dd51ae1)：密集路网渲染、缓存与分配优化。
- [`29cc313`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/29cc313)：动态贴图与轨道遮挡任务解耦。
- [`445978e`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/445978e)：限制遮挡扫描并优先处理当前请求。
- [`f723771`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/f723771)：Iris 排序、模型绘制分配及查询缓存优化。
- [`f22d8ff`](https://github.com/Yanyang-Technology-Group/MTR-YanYang/commit/f22d8ff)：服务端有界并行寻路集成。
- TSC [`3ab979e`](https://github.com/Yanyang-Technology-Group/Transport-Simulation-Core/commit/3ab979e)：独立的批量并行路径计算模块。

### 许可证与致谢

本项目采用 MIT 许可证，随附的 Noto 字体采用 SIL Open Font License。感谢上游 MTR、TSC 及相关依赖项目的开发者。本仓库为社区分支，并非上游官方发行版。

## English

MTR-YanYang is a community fork of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), based on MTR 4.0.5. The current optimization and validation target is **Minecraft 1.20.1 Fabric**.

The fork focuses on dense stations, platforms, tracks and trains. MTR 4.0's underlying architecture comes from upstream; this fork adds:

- Spatial indexing, rail geometry and route-map span caches, and reusable render callbacks.
- Iris render-order caching and array-based graph traversal that preserves the original candidate search and ordering rules.
- Path ID caching, reusable matrix upload buffers, and fewer temporary arrays in render-state hashing; bounded rail ID caching for the optional MAGIC integration.
- Independent scheduling for dynamic textures so station names and route maps do not wait behind rail occlusion work.
- Bounded occlusion scans, prioritization of current visibility requests, and per-pass opacity lookup caching.
- Improvements to arrival information caching and vehicle/lift update handling.
- Bounded server-side parallel path computation using the standalone TSC module: read-only graph snapshots, request coalescing, batches, version invalidation, and ordered result assembly and commits on the server thread.

Parallelization applies to path computation, not the entire vehicle simulation or Minecraft world updates. Unsupported inputs fall back to the original serial path. Performance depends on the workload, hardware and mod combination; no fixed FPS or TPS gain is guaranteed.

### Installation

Download the Fabric 1.20.1 runtime jar from [Releases](https://github.com/Yanyang-Technology-Group/MTR-YanYang/releases), install Fabric Loader and Fabric API, place the jar in `mods`, and restart. Keep only one MTR runtime jar when replacing an existing installation. Do not install the sources jar. The latest repository commits may not yet be included in a published release.

### Server path computation options

Add JVM options before `-jar` and restart after changing them:

- `-Dmtr.path.parallel=false`: disable parallel path computation; enabled by default.
- `-Dmtr.path.workers=8`: choose the worker count. Automatic selection uses at most 8 workers and leaves two JVM-visible processors unused by this pool; manual selection is capped at 12.
- `-Dmtr.path.metrics=true`: enable statistics logged approximately every 30 seconds; disabled by default.

The integration is disabled when at most two processors are available to the JVM or the existing threaded simulation mode is enabled. The queue holds 32 tasks, with up to 8 requests per batch. More workers do not necessarily improve performance.

### Building

Check out [Transport-Simulation-Core](https://github.com/Yanyang-Technology-Group/Transport-Simulation-Core) beside `Minecraft-Transit-Railway`, as shown in the directory layout above. The composite build imports only its standalone Java 17 `path-computation` module. Build tooling requires JDK 21, with a Java 17 toolchain for the Fabric 1.20.1 target, plus the project's existing MTR mapping and build dependencies.

Run `./gradlew :fabric:test :fabric:build` from the MTR repository. Release artifacts are written to `build/release/`. The commit index above links to the optimization history in both repositories.

### License and acknowledgements

This project is licensed under MIT. Bundled Noto fonts use the SIL Open Font License. Thanks to the upstream MTR and TSC developers and dependency maintainers. This is a community fork, not an official upstream release.
