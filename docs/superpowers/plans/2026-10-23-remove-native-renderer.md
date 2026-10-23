# 移除原生渲染实现计划

> 执行方式：实现阶段在当前工作区使用 executing-plans 内联执行，不提交、不推送、不创建分支。后续提交、推送与分支历史整理由维护者另行授权，不属于本计划的实现阶段。

**目标：** 清除 C++、JNI 及 Java-flat 接入，恢复 `33553ec7` 的 Java 模型加载和绘制路径。

**依据：** 维护者已确认恢复 Java 实现并彻底清除 C++；明确不需要迁移用户已保存的配置。

**边界：** 保留发布命名、发布工作流修复、Javadoc 编码、网站忽略规则，以及加载结束时的 `finally` 清理。保留既有服务端优化和新增贡献指南。

## 任务 1：恢复 Java 调用链

- [x] 检查当前工作区、基线差异及现有模型与渲染测试。
- [x] 从 `33553ec7` 恢复模型加载器、模型与渲染包装器、模型资源类型、配置与界面；使用 apply_patch 应用恢复差异。
- [x] 在 `StoredModelResourceBase` 和 `VehicleResource` 中恢复 Java 模型类型与构建入口，保留现有 `try/finally` 对 `finishReload()` 的保障。
- [x] 删除 `JavaObjModel`、`OptimizedObjModel`、`nativeobj` 生产与测试包，移除原生开关、翻译及 schema 字段。

## 任务 2：清除构建与文档接入

- [x] 删除 `native/` 全部源码、测试和工具，以及 `.github/workflows/native.yml`。
- [x] 恢复 Fabric Gradle 配置，删除原生资源打包和仅服务于原生诊断的 classpath 导出任务。
- [x] 删除原生性能说明；恢复 README，修订贡献指南，去除失效的原生构建步骤及链接。
- [x] 删除 `.gitignore` 中的项目原生构建规则，保留网站相关忽略项。

## 任务 3：验证与审查

- [x] 搜索项目确认原生模块、开关和 Java-flat 接入没有残留；检查 JSON、文档链接及 `git diff --check`。
- [x] 执行 Fabric 1.20.1 的 `setupFiles`、`test`、`build`，检查测试 XML 与最终 JAR，不将缺失或跳过的验证计为成功。
- [x] 执行 Fabric 1.20.4 的相同构建与测试，验证共用模型代码的兼容性。
- [x] 执行 Fabric 1.20.1 服务端优化集成测试，检查 `MTR_SERVER_OPTIMIZATION_INTEGRATION_OK` 标记。
- [x] 按 requesting-code-review 技能进行独立审查，处理实际问题；记录无法完成的游戏内验证。

验证命令：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test :fabric:build -PmodLoader=fabric -PminecraftVersion=1.20.1 --console=plain --max-workers=4
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:test :fabric:build -PmodLoader=fabric -PminecraftVersion=1.20.4 --console=plain --max-workers=4
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :fabric:setupFiles :fabric:runServerOptimizationTest -PmodLoader=fabric -PminecraftVersion=1.20.1 -PserverOptimizationTest --console=plain --max-workers=4
```

## 已完成的验证

- Fabric 1.20.1 完整构建成功；121 项测试，0 失败、0 错误、0 跳过。
- Fabric 1.20.4 完整构建成功；121 项测试，0 失败、0 错误、0 跳过。
- 两版本发布 JAR 均不包含项目原生资源、`nativeobj`、`FlatDrawPass` 或已删除的模型适配类。
- Fabric 1.20.1 服务端集成测试成功，输出 `MTR_SERVER_OPTIMIZATION_INTEGRATION_OK`。
- 独立静态审查未发现遗漏；未进行真实客户端渲染验证。
