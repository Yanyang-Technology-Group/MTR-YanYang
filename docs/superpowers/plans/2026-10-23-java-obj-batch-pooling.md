# Java OBJ 批次列表复用计划

**目标：** 减少 Java OBJ 逐帧绘制中材质批次列表和扩容数组的重复分配。

**设计：** 保留原 `BatchManager` 的材质 HashMap、队列顺序、每次模型入队的矩阵快照和 `RenderCall.draw()`。仅替换材质列表的创建，以及原有清空位置的释放操作。复用池按线程隔离，并限制列表数量、单列表容量和总引用槽位。

**范围依据：** 维护者已授权在纯 Java 基础上继续优化 OBJ 渲染。此前 C++ 清理保持生效，不新增原生模块或替代 GL 绘制循环。

## 实施与验证

- [x] 检查现有优化和两个版本实际后端字节码，确认目标调用点一致。
- [x] 先编写批次引用释放、存储复用、容量上限、线程隔离和外部列表兼容测试，观察实际失败。
- [x] 新增 `RenderBatchListPool`，使用私有列表类型识别所有权，回收时清空所有渲染调用引用。
- [x] 在现有 `OptimizedBatchManagerMixin` 中仅修改 `computeIfAbsent` 的列表工厂与原有 `Map.clear()`，包括未绘制透明队列。
- [x] 验证两版本注入目标；通过隔离 Fabric 客户端 preLaunch 测试确认实际 Mixin 生效，无需创建 GL 窗口。
- [x] 增加可复现的批次存储 A/B 基准，报告真实分配量与 CPU 耗时，注明没有测游戏 FPS。
- [x] 运行 Fabric 1.20.1 和 1.20.4 的完整测试、构建与集成检查，进行独立审查并记录结果。

结果：两版本各 129 项单元测试通过，0 失败 / 错误 / 跳过；构建和真实客户端 Mixin 队列集成检查通过，发布包内容检查通过。独立审查未发现需要修复的问题。微基准及验证边界记录于 `docs/performance/2026-10-23-java-obj-batches.md`；尚未完成游戏画面 / 光影验证或 FPS 测量。

## 关键约束

- 每帧仍移除全部材质 key，避免长期持有可变纹理和改变 HashMap 迭代顺序。
- 不在绘制结束之前回收在用列表；绘制异常时保留原有清空时机。
- 不复用矩阵快照或 `VertexAttributeState`，不改变颜色、光照、透明顺序及 GL 属性应用。
- 空列表不占用池空间，超大或超量列表清空后丢弃；不回收其他修改器提供的列表。
- 实现阶段不提交、推送或重置已有工作区改动；后续发布操作由维护者另行授权。

## 验证命令

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test --tests '*RenderBatchListPoolTest' -PmodLoader=fabric -PminecraftVersion=1.20.1 --console=plain --max-workers=4
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:runRenderBatchBenchmark -PmodLoader=fabric -PminecraftVersion=1.20.1 --console=plain --max-workers=4
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test :fabric:build :fabric:runRenderBatchTest -PrenderBatchTest -PmodLoader=fabric -PminecraftVersion=1.20.1 --console=plain --max-workers=4
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test :fabric:build :fabric:runRenderBatchTest -PrenderBatchTest -PmodLoader=fabric -PminecraftVersion=1.20.4 --console=plain --max-workers=4
```
