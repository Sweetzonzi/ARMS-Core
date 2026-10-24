# BodyPart 最小接入

## 1. 当前范围

`BodyPart` 是 `AbstractPart<BodyPartType>` 的实现。ArmsCore 持有身体部件集合与核心引用，身体部件具有真实的 SubPart、刚体和保存状态。身体刚体不加入物理世界，角色运动由 `common/control/MechaCharacter.java#MechaCharacter` 承担；整机画面由 `client/MechaPlayerRenderer.java#MechaPlayerRenderer` 绘制。

本文源码坐标相对于 `src/main/java/io/github/sweetzonzi/arms_core/`；以 `../Machine-Max/` 开头的坐标相对于仓库根。

本阶段不提供身体连接、关节驱动、身体伤害向宿主转发、身体属性字段或宿主 NBT 的自动持久化。`ArmsBodyData` 是部件组成快照，不包含宿主身份、角色控制器位姿或逻辑状态。

## 2. 固定结构

内容包的 `arms_core/parts/minimal_body.json` 只填写 `type: arms_core:body`。`common/part/BodyPartType.java#CODEC` 根据加载上下文取得对应逻辑端的固定预设，不向作者暴露身体专属配置字段。

预设位于 `common/part/BodyPartPreset.java#variant`，客户端与服务端各缓存一份 VariantAttr，包含：

- 名为 `body` 的一个 SubPart；start_bone 与 HitBox 骨骼都为 `body`。
- 正质量 1 kg、耐久 20、一个 box 形状；没有连接点、子系统和交互区。
- 模型索引 `part / arms_core:minimal_body`，没有纹理资源，运行时沿用公共类型的缺省纹理标识。

模型文件为 `src/main/resources/spark_modules/arms_core.builtin/arms_core/models/part/minimal_body.json`，包含单骨骼与一个 16×16×16 模型单位的 cube，省略 UV。加载器按每格 16 模型单位换算，碰撞盒边长为 1 m。description 中的纹理尺寸仅满足模型读取格式。

同端所有 BodyPart 共用 SubPartAttr 和碰撞形状；每个实例独立持有 SubPart、刚体、耐久和归属。这里的共享对象是结构定义，不是运行时子部件。缓存不提供资源重载失效机制。

类型注册在 `ARMS.java#commonSetup` 的串行工作中执行，入口为 `common/part/BodyParts.java#register`。固定定义的构造依赖已加载的模型，不能在模组构造阶段创建预设。

## 3. 装配体与生命周期

`common/ArmsCore.java#corePart` 是非空的 final 引用，新建构造器接收调用者准备好的 BodyPart，并将其加入成员集合。反序列化构造器接收 ArmsBodyData，从保存数据确定核心。更换核心需要创建另一具装配体。

`common/ArmsCore.java#partMap` 是 final 的只读视图，其背后的 `#mutableParts` 也是 final 容器。主线程增删成员时只修改容器内容；调用者保留的视图会看到成员变化。核心始终是成员集合中的同一个实例，直到整个装配体释放。

`common/ArmsCore.java#addPart` 校验类型、世界、UUID、归属、有效结构及刚体未入世。当前装配体只接受 BodyPartType。核心禁止单独移除；其他成员移除时清理归属并销毁子结构。

`common/ArmsCore.java#getTotalMass` 返回部件总质量，不修改角色控制器的质量。`#getRootSubPart` 返回核心的根 SubPart。`#connect` 与 `#disconnect` 明确报告不支持。

`common/part/BodyPart.java#addToLevel` 拒绝身体占位刚体入世。`#CONTEXT` 固定禁止撬棍拆卸。宿主装配体不创建 MMPartEntity，部件不进入 MM 普通零部件与制造蓝图列表。

主线程在 `common/ArmsCore.java#tickBody` 推进部件 tick。注册表移除与维度清理通过 `#disposeBody` 清理部件。`/arms spawn` 的 `common/command/ArmsCoreDebugCommand.java#spawn` 创建默认核心部件。

## 4. 保存、恢复与同步

`common/part/BodyPartData.java#CODEC` 保存公共身份、变体、纹理标识、共享耐久比例与 SubPart 状态，不包含制造进度。

`common/part/ArmsBodyData.java#CODEC` 保存装配体名称、必填核心 UUID 与成员列表。成员集合必须包含核心，成员 UUID 必须唯一；空集合不是合法组成。

`common/ArmsCore.java#saveBody` 与 `#saveBodyTag` 生成组成快照。`#restoreMembers` 在构造前校验全部定义和结构，再恢复完整成员；失败时释放已分配实例。身体状态必须包含定义的全部 SubPart，避免把缺失状态解释为身体被移除。通用工厂的完整恢复与骨架恢复由 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/PartRestoreMode.java#PartRestoreMode` 区分。

`common/ArmsCore.java#applyBodySnapshot` 只接受同一核心身份的快照。整批成员先恢复到临时实例完成校验，随后通过 `common/part/BodyPart.java#applySavedState` 更新现有核心的状态、释放临时核心并更新成员容器。核心引用及其刚体保持不变，其他成员使用恢复出的实例。无效定义、缺失结构或核心身份变化会被拒绝，现有成员保持有效。

创建包 `network/payload/ArmsCoreCreatePayload.java#body` 携带身体组成 NBT，协议版本为 `network/ARMSNetwork.java#PROTOCOL_VERSION` 的 `arms_core:3`。登录补发使用同一个创建包；成员变化在下一主线程 tick 发送完整组成快照。客户端由 `common/MechaCoreRegistry.java#queueClientSnapshot` 暂存请求，定义发布后通过 `#flushClientSnapshots` 一次性构造核心与成员。等待期间的同步增量由 `#queuePendingSync` 合并，移除包或维度清理会取消待创建请求。此同步不将占位身体接入整机渲染。

## 5. 验证

- `src/test/java/io/github/sweetzonzi/arms_core/common/part/ArmsBodyDataTest.java#compositionCodecPreservesCoreSelectionAndRejectsAmbiguousIdentities`：组成 Codec、核心引用、UUID 冲突和空集合。
- 同文件 `#collisionModelIsOneBoneOneCubeWithoutUvOrTextureAssets`：单骨骼、单 cube 和 UV 省略。
- `src/test/java/io/github/sweetzonzi/arms_core/network/ARMSNetworkCodecTest.java#createPayloadCarriesBodyCompositionAfterSyncedFields`：创建包携带 NBT、输入快照复制和缓冲完整消费。
- `common/gametest/BodyPartGameTest.java#bodyCompositionRoundTripsWithoutEnteringPhysics`：真实内容包加载、同端形状共享、双端预设分离、实例独立、完整与骨架恢复、无效快照拒绝、核心禁止移除，以及同步前后核心／核心刚体／成员视图的身份稳定性。身体刚体全程不入世。

JUnit 使用 `gradlew --offline test`，服务端集成使用 `gradlew --offline runGameTestServer`。GameTest 验证本阶段结构与状态契约，不测量角色位移或动画关节跟随。
