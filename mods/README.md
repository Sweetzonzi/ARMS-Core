# mods/ — 双端开发期模组

把「客户端与专用服务器都要」的第三方 mod jar 直接放进这个目录：文件名以 `.jar` 结尾就会被 `devMods` 收走，
不需要改任何 Gradle 配置。当前开发环境放的是：

| jar | 用途 |
|-----|------|
| `curios-neoforge-9.5.1+1.21.1.jar` | Curios API：饰品/挂载点 |
| `superbwarfare-1.21.1-0.8.9-snapshot.jar` | SuperbWarfare：内容模组，用于验证兼容层 |

加载范围：`runClient`、`runServer`、`runGameTestServer`、`runData` 四个 run 全部加载。挂载点是 main 的
`runtimeClasspath`（run 的 JVM 类路径就是它），理由见 `build.gradle` 里「开发期 mod」那一节。

**jar 不入库**：`.gitignore` 忽略 `/mods/*.jar`，本仓库只提交这份 README。clone 之后需要自己下载放进这个
目录；Machine-Max 仓库的 `mods/` 就是同一套布局，两边保持一致即可。版本以上表为准。

**不要放的东西**：

- **GeckoLib**：Spark-Core 与 Machine-Max 的 Maven 依赖 `software.bernie.geckolib:geckolib-neoforge-<mc>:<geckolib_version>`
  已经提供它，再放一份 jar 会让同一个包被两个模块导出，ModLauncher 在模块解析阶段直接中止。
- **Spark**：Spark-Core 的构建脚本用 `implementation(files(fileTree("mods")))` 把它带进了本项目的运行期类路径，
  本地再放一份同名模块就会撞包。
- **仅客户端的 mod**（DistantHorizons、Iris、Sodium）：放 `../mods-client/`。它们会让 `runServer` /
  `runGameTestServer` 启动失败。

目录为空、甚至整个目录不存在都可以：`fileTree` 对空目录返回空集合，这四个 run 只是少加载几个 mod。
