# Java OBJ 材质批次列表复用

## 改动范围

本轮只减少 Java OBJ 逐帧入队时材质批次列表及其扩容数组的分配，不重写绘制循环，不新增 JNI / C++。

检查两个版本随仓库提供的 `Minecraft-Mappings-fabric-1.20.{1,4}-0.0.1-dev.jar` 后，确认实际调用链为：

```text
OptimizedRenderer.queue
  → 保存每次模型入队的 VertexAttributeState / 矩阵快照
  → BatchManager.queue
  → 按原有 MaterialProperties HashMap 分组
  → drawBatch
  → 原有 RenderCall.draw
```

原 `queue` 的 `Map.computeIfAbsent` 为每个材质批次创建 `java.util.ArrayList`。`drawBatch` 在迭代绘制完成后清空材质 Map，因此下一帧重新创建列表并逐步扩容。本轮复用这些列表存储，而不是减少模型数量、合并材质或跳过绘制。

现有 Java `RawMesh.upload` 已使用 `OffHeapAllocator.allocate/free` 准备上传缓冲区；不能把“Java 改用 direct buffer”当作本轮新增收益。

## 实现与生命周期

- `OptimizedBatchManagerMixin` 仅替换 `computeIfAbsent` 的列表工厂，以及原 `drawBatch` 的 `Map.clear()` 调用；原有 `drawAll(false)` 的透明队列清理也进行回收。
- 原材质 Map、HashMap 迭代机制、列表内部调用顺序、状态与矩阵快照保持不变。`RenderCall.draw()` 中的 VAO 绑定、实例属性、材质属性及 draw 调用未修改。
- `RenderBatchListPool` 使用线程局部池和私有 `ObjectArrayList` 子类，出池后不能再次借出同一列表，直到该批次完成并回收。
- 回收时先清空所有渲染调用引用，再保留空的底层数组。不会缓存材质 key、模型、矩阵或 GPU 资源；每帧仍然移除材质 Map 的所有 key。
- 每线程最多保留 256 个列表、每列表最多 65,536 个引用槽位、合计最多 262,144 个引用槽位。采用 4 字节压缩引用时，底层数组内容约 1 MiB，另有列表、数组头与队列开销；8 字节引用时约 2 MiB。
- 超出上限的列表清空后丢弃；外部修改器提供的普通列表仅从 Map 移除，不修改其内容。不同池之间不转移列表所有权。
- 绘制异常时不会提前回收仍在队列中的列表，保持原 `drawBatch` 在绘制完成后才清空的时机。

## A/B 测量方法

基准源代码为 `fabric/src/test/java/org/mtr/mod/render/RenderBatchBenchmark.java`，比较以下两条批次存储路径：

1. 基线：与随仓库后端一致的 `HashMap.computeIfAbsent(..., material -> new ArrayList<>())`，绘制迭代后 `Map.clear()`。
2. 修改：相同 HashMap、输入及迭代工作量，使用生产 `RenderBatchListPool.factory()` 和 `RenderBatchListPool.clear()`。

测量时仓库 HEAD 为 `a01e5341116cf881dd44c8de9b9b707068f9f2e3`，工作区同时包含此前已授权的原生后端删除，因此该提交本身不是纯 Java 对照版本。基准在同一进程中执行上述明确给出的旧 Java 存储算法与修改后的生产存储算法，不拿原生后端结果作为 Java 基线。

测量环境：Linux x86-64、AMD Ryzen 5 5600、Zulu OpenJDK 17.0.20.1，堆参数 `-Xms512m -Xmx512m`。Gradle 使用 JDK 21，基准进程使用项目的 Java 17 工具链。

每组预热 2,000 帧，每轮测量 1,500 帧，测量 7 轮并交替旧 / 新路径的先后顺序，报告中位数。使用 `ThreadMXBean.getThreadAllocatedBytes` 记录线程堆分配量，`System.nanoTime` 记录耗时；两条路径都遍历全部调用并验证校验和。

**边界：** 这是批次存储微基准。材质 key 和调用载体预先创建，不包含实际 `MaterialProperties` 哈希、矩阵复制、`VertexAttributeState` / `RenderCall` 创建、模型加载上传、GL 调用或 GPU 工作量。它不能证明完整渲染耗时或游戏 FPS 提高相同比例，也不替代 JMH 多进程测量或游戏实测。

复现命令：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:runRenderBatchBenchmark -PmodLoader=fabric -PminecraftVersion=1.20.1 --console=plain --max-workers=4
```

`JAVA_HOME` 路径按本机安装调整；使用项目现有工具链选择规则启动 Java 17。

## 首次测量结果

| 调用数 / 帧 | 材质数 | 旧分配 B / 帧 | 新分配 B / 帧 | 旧耗时 ns / 帧 | 新耗时 ns / 帧 |
| --- | --- | --- | --- | --- | --- |
| 1,000 | 1 | 15,056 | 44 | 11,310 | 9,738 |
| 10,000 | 1 | 169,000 | 32 | 109,868 | 96,652 |
| 10,000 | 32 | 146,432 | 1,024 | 104,092 | 91,333 |
| 10,000 | 256 | 169,984 | 8,192 | 112,964 | 107,843 |

原始标准输出：

```text
Java 17.0.20.1, OpenJDK 64-Bit Server VM
Batch storage only; preallocated keys/calls, no matrices or GL; warmup=2000 frames, samples=7 x 1500 frames
calls,materials,baseline_bytes/frame,pooled_bytes/frame,baseline_ns/frame,pooled_ns/frame
1000,1,15056,44,11310,9738
10000,1,169000,32,109868,96652
10000,32,146432,1024,104092,91333
10000,256,169984,8192,112964,107843
checksum=2106783000
```

以 10,000 次调用、32 个材质为例，本机稳态批次存储分配从 143 KiB / 帧降至 1 KiB / 帧，减少约 99.3%；该子阶段耗时从 0.104 ms 降至 0.091 ms。收益主要是减少临时容器及扩容数组，不能将其写成“整体渲染零分配”或“FPS 提升 12%”。HashMap 节点、实例状态、矩阵和渲染调用仍会分配。

新场景、材质数量变化或调用数量变化仍可能扩容；超过池容量上限时也会继续分配。上述结果仅代表预热后的给定工作负载，不承诺所有资源包都有相同收益。

切换到 1.20.4 构建配置后，在新的 Java 17 进程中复测同一存储基准，输出如下。此处的版本仅标识构建配置，并非游戏场景；耗时波动不能解读为两个 Minecraft 版本的渲染性能差异。

```text
calls,materials,baseline_bytes/frame,pooled_bytes/frame,baseline_ns/frame,pooled_ns/frame
1000,1,15056,46,13881,9685
10000,1,169000,32,102136,93700
10000,32,146432,1024,97959,83501
10000,256,169984,8192,106979,100754
checksum=2106783000
```

## 验证方式

- 8 项 `RenderBatchListPoolTest` 检查引用释放与存储复用、活动列表隔离、总槽位 / 单列表 / 数量上限、连续帧调用顺序、外部列表、重复回收、线程和池所有权。
- `OptimizedBackendCompatibilityTest` 检查两个映射 JAR 的准确队列描述符、列表工厂调用点、静态 `drawBatch` 及其清理调用点。
- 独立 `renderBatchTest` source set 不携带单元测试的 Iris 运行依赖。在真实 Fabric 客户端 `preLaunch` 中加载已经应用生产 Mixin 的 `BatchManager`，检查实际入队、材质相等分组、调用顺序、实例状态身份、多管理器在用隔离、多帧透明队列清理及底层数组复用。
- 正常批次回收测试调用真实 `drawBatch`，以只记录迭代边界的 Map 替身代替 GPU 绘制回调，验证列表在原清空调用点才被释放。这部分不执行 shader / VAO / GL。
- 集成测试成功后写入唯一标记；Gradle 每次运行前删除旧标记，进程退出后检查内容，防止提前退出被误判为成功。

完整验证命令：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test :fabric:build :fabric:runRenderBatchTest -PrenderBatchTest -PmodLoader=fabric -PminecraftVersion=1.20.1 --console=plain --max-workers=4
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test :fabric:build :fabric:runRenderBatchTest -PrenderBatchTest -PmodLoader=fabric -PminecraftVersion=1.20.4 --console=plain --max-workers=4
```

2026-10-23 的实际验证结果：

| 目标 | 单元测试 | 失败 / 错误 / 跳过 | 构建 | 真实 Mixin 队列集成 |
| --- | --- | --- | --- | --- |
| Fabric 1.20.1 | 129 | 0 / 0 / 0 | 成功 | `MTR_RENDER_BATCH_INTEGRATION_OK` |
| Fabric 1.20.4 | 129 | 0 / 0 / 0 | 成功 | `MTR_RENDER_BATCH_INTEGRATION_OK` |

检查两版本发布 JAR，均包含生产复用池和 Mixin，不包含集成测试入口、基准程序或此前删除的原生 OBJ 后端。上述验证在提交前的工作区中完成，后续发布及分支历史整理由维护者另行授权。

尚未执行真实资源包的画面对照、光影兼容验证或游戏帧时间 / FPS 测量；本记录不据此宣称完整客户端视觉验证。
