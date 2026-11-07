# BodyPart 与素体装配实施方案

> 定义加载、素体新建、存档恢复和玩家身体入世已接线。身体核心跟随控制器位姿。内置 AllBody 由整机动画驱动四个髋膝关节，客户端按同步位姿逐部件显示，见 §6.3。断开分裂待接入。

## 1. 范围与职责

| 层 | 权威数据 | 用途 |
|---|---|---|
| BodyPart 定义 | 型号、命名变体、模型、各 SubPart 的刚体与挂点 | 定义一件可整体换装的部件。 |
| 素体定义 | 核心成员、默认部件及变体、成员间的连接列表 | 用同一组部件生成不同的初始身体。成员可为 BodyPart 或 RegularPart。 |
| ArmsCore 实例与存档 | 可选素体 ID、部件 UUID、实际核心、无向装配图、运行状态 | 表达某一具身体，包括实际换装、缺肢、闭环连接及当前位姿。 |

例如「肉腿素体」和「义肢素体」可以共用躯干部件定义，各自选择不同的腿部型号与变体。躯干只声明髋部挂点；载入存档时按当时实际装上的腿恢复。

本文 ARMS 源码坐标相对于 `src/main/java/io/github/sweetzonzi/arms_core/`，以 `../` 开头的坐标相对于仓库根。`common/part/BodyPartType.java#CODEC` 解码部件变体，`common/part/ArmsFrame.java#CODEC` 解码素体结构，`common/part/ArmsFrameModule.java#publish` 发布通过引用校验的素体。`common/ArmsCore.java#fromFrame` 根据素体新建机体，`common/ArmsCore.java#enterPhysicsSpace` 统一管理物理入世。现有实例契约见 `docs/BodyPart最小接入.md` §1、§3、§4。

首版验证被动关节、混装与连通性判定。暂不接管正常行走和宿主位置，也不引入换装 UI、游戏内实时换装、残件玩法、伤害转发、能量网络、IK、马达参数 JSON 或资源热重载。BodyPart 禁止撬棍拆卸；ArmsCore 的身体与附件由自己的同步、绘制路径管理，首版均不创建 MMPartEntity。

## 2. BodyPart 作者格式

### 2.1 文件与变体

部件文件放在内容包的 `<namespace>/parts/<name>.json`，沿用 MM 的文件名注册规则，ID 为 `<namespace>:<name>`。顶层必填 `"type": "arms_core:body"`。类型分发继续走 `../Machine-Max/src/main/kotlin/io/github/sweetzonzi/machine_max/common/resource/modules/PartModule.kt#read`；该模块把文件分派给对应种类的 Codec。

顶层必填 `variants`，直接使用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/AbstractPartType.java#VARIANT_MAP_CODEC`。其值可以是一个变体对象（名称为 `default`），也可以是非空的「变体名 → 完整变体」映射。变体之间不继承；引用方省略变体时取 `default`，若未定义则报告错误。BodyPart 的种类负责生命周期和存档分派；载具整车伤害系数等 RegularPart 专属字段不属于 BodyPart 定义。

例如 `arms_core:demo_torso`：

```json
{
  "type": "arms_core:body",
  "variants": {
    "model": "arms_core:demo_torso",
    "sub_parts": {
      "body": {
        "start_bone": "torso",
        "mass": 18.0,
        "hit_boxes": { "torso_box": { "type": "box" } },
        "connectors": {
          "left_hip": { "locator": "left_hip", "definition": "arms_core:body_hinge" },
          "right_hip": { "locator": "right_hip", "definition": "arms_core:body_hinge" }
        }
      }
    }
  }
}
```

例如 `arms_core:demo_leg`，两个变体都公开名为 `hip` 的接口，但可使用各自模型内的 Locator 名：

```json
{
  "type": "arms_core:body",
  "variants": {
    "left": {
      "model": "arms_core:demo_leg_left",
      "tags": ["arms_core:leg", "arms_core:left"],
      "sub_parts": {
        "body": {
          "start_bone": "leg",
          "mass": 5.0,
          "hit_boxes": { "leg_box": { "type": "box" } },
          "connectors": {
            "hip": { "locator": "hip_left", "definition": "arms_core:body_mount" }
          }
        }
      }
    },
    "right": {
      "model": "arms_core:demo_leg_right",
      "tags": ["arms_core:leg", "arms_core:right"],
      "sub_parts": {
        "body": {
          "start_bone": "leg",
          "mass": 5.0,
          "hit_boxes": { "leg_box": { "type": "box" } },
          "connectors": {
            "hip": { "locator": "hip_right", "definition": "arms_core:body_mount" }
          }
        }
      }
    }
  }
}
```

### 2.2 变体与刚体字段

| 字段 | 必填与默认值 | 语义 |
|---|---|---|
| `model` | 必填 | 模型资源 ID，沿用 MM 的 `part` 模型索引。 |
| `textures`、`animations` | 可省略 | 沿用 MM 变体对应字段的格式和缺省资源；第一具调试身体允许没有贴图和动画。 |
| `tags` | 可省略，默认空 | MM 的变体标签，用于连接兼容条件。 |
| `start_bone` | MM 默认空 | 本子结构的起始骨骼；非空时必须存在。调试夹具显式填写，以确定局部动画的参考骨骼。 |
| `end_bones` | 可省略，默认空 | 按 MM 的骨骼范围约定排除不属于本子结构的分支。 |
| `mass` | MM 默认 25 | 有限正数，单位 kg。测试支架固定躯干由测试场景负责，不能把部件定义质量写成零。 |
| `durability` | MM 默认 20 | 局部结构耐久定义；首版使用 BodyPart 的共享耐久策略。 |
| `hit_boxes` | 每个 SubPart 至少一个 | 直接使用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/HitBoxAttr.java#MAP_CODEC`。键为形状骨骼名，值为 MM 的命中框对象，仅 `type` 必填。位置、尺寸来自模型几何；材料、厚度和启用条件沿用 MM 字段与默认值。 |
| `connectors` | 可省略，默认空 | MM 连接点。用于部件内部关节、部件间关节及附件连接。 |

变体的 `sub_parts` 直接使用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/VariantAttr.java#SUB_PART_MAP_CODEC`：可以写命名映射，也可以直接写一个 SubPart 对象，后者的名称为 `sub_part.machine_max.main`。示例显式命名为 `body` 以便素体引用。同一部件的不同变体可以采用不同刚体数量；引用方按选定变体校验具体端点。

例如可整体换装的手臂 `arms_core:demo_arm`，肩部为外部挂点，肘部两个 Locator 在模型的静止姿势中按配对约定对齐：

```json
{
  "type": "arms_core:body",
  "variants": {
    "model": "arms_core:demo_arm",
    "sub_parts": {
      "upper_arm": {
        "start_bone": "upper_arm",
        "end_bones": ["forearm"],
        "mass": 3.0,
        "hit_boxes": { "upper_arm_box": { "type": "box" } },
        "connectors": {
          "shoulder": { "locator": "shoulder", "definition": "arms_core:body_mount" },
          "elbow_socket": {
            "locator": "elbow_socket",
            "definition": "arms_core:body_hinge",
            "internal": true
          }
        }
      },
      "forearm": {
        "start_bone": "forearm",
        "mass": 2.0,
        "hit_boxes": { "forearm_box": { "type": "box" } },
        "connectors": {
          "elbow_plug": {
            "locator": "elbow_plug",
            "definition": "arms_core:body_mount",
            "internal": true
          }
        }
      }
    }
  }
}
```

共享结构字段由 MM Codec 直接解码。形状类型、材料及同一 SubPart 内的子系统引用沿用 MM 校验；首个实验夹具使用 `box`。定义和碰撞形状按逻辑端共享，实例独立持有 SubPart 与刚体。结构构造见 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/AbstractPart.java#createSubParts`，命中框引用规则见 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/HitBoxAttr.java#validate`。内置 `minimal_body` 写成显式单变体定义，保留注册名、`default` 变体、`body` 子结构和模型几何，供只测试控制器的入口使用。

### 2.3 连接兼容与内部关节

- `connectors` 直接使用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/connector/ConnectorAttr.java#MAP_CODEC`；`locator` 和 `definition` 必填，覆写沿用 MM 字段。Locator 指定模型内的端点参考系；`definition` 指定端点类型、法线、兼容条件、关节轴和限位。素体的连接列表指定哪两个端点实际相连。
- 用 MM 的 `required_tags`、`acceptable_tags`、`forbidden_tags` 匹配对侧变体的 `tags`。例如左髋可要求对侧同时有 `arms_core:leg` 与 `arms_core:left`。素体发布使用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/connector/ConnectorAttr.java#matchesTags`，不读取服务器配置；游戏内普通连接的绕过开关见同文件 `#conditionCheck`。标签表达适配性；端点存在、归属、占用、类型及装配图另行校验。
- 同一张 `connectors` 表供身体部件和普通部件使用。外部连接至少一端是 SimpleConnector；如另一端是 AdvancedConnector，其关节属性生效。多 SubPart 部件用 `internal: true` 声明内部配对端，沿用 MM 的对齐和兼容规则。内部端点不能用于素体的部件间连接。
- MM 以变体质量最大的 SubPart 作部件根；首版多刚体变体要求有唯一最大质量，保证作者可预测根。内部连接未匹配、存在歧义，或构造后有子刚体无法通过内部关节到达部件根时，整件身体构造失败。MM 的配对本身可能只记录日志，ARMS 的工厂须在装配体发布前核对实际配对结果，不能把孤立子刚体放进世界。

配对实现参考 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/AbstractPart.java#autoAttachInternalConnectors`；外部连接契约参考 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/connector/AbstractConnector.java#attachJoint`。挂点名称在同一变体内唯一，遵守 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/VariantAttr.java#validateUniqueNames` 的保留名和去重规则。连接仍使用「SubPart 名 + 挂点名」定位并核对端点，避免变体修改子结构时静默接错刚体。

示例使用 `arms_core:body_mount` 和 `arms_core:body_hinge` 表示简单端与高级端；它们是说明用的 ID。实际内置夹具使用 `arms_core:allbody_body_plug`、`arms_core:allbody_hip_hinge`、`arms_core:allbody_knee_hinge`，字段沿用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/connector/ConnectorStaticAttr.java#CODEC`。夹具的髋与膝都开放 `xr`，限位仅用于验证约束。实际成员和端点见 `src/main/resources/spark_modules/arms_core.builtin/arms_core/arms_frames/allbody_joint_test.json`。

## 3. 素体格式与发布

### 3.1 默认装配

素体文件放在 `<namespace>/arms_frames/<name>.json`。素体 ID 由命名空间和文件名确定。首版只定义默认装配；例如 `arms_core:demo_biped`：

```json
{
  "core": "torso",
  "core_height_offset": 0.44,
  "parts": {
    "torso": { "part": "arms_core:demo_torso" },
    "left_leg": { "part": "arms_core:demo_leg", "variant": "left" },
    "right_leg": { "part": "arms_core:demo_leg", "variant": "right" }
  },
  "connections": [
    {
      "a": { "member": "torso", "sub_part": "body", "connector": "left_hip" },
      "b": { "member": "left_leg", "sub_part": "body", "connector": "hip" }
    },
    {
      "a": { "member": "torso", "sub_part": "body", "connector": "right_hip" },
      "b": { "member": "right_leg", "sub_part": "body", "connector": "hip" }
    }
  ]
}
```

- `core` 引用本文件中一个 BodyPart 成员，确定装配锚点和分裂时保留身份的部件；部件定义不需要 `core: true`。
- `core_height_offset` 是从控制器中心到核心质心的竖直高差，单位为米，正值抬高核心，缺省为 `0`。内置 AllBody 按默认装配的碰撞体底面标定为 `0.44`。
- `parts` 的每项引用一个已发布的 BodyPart 或 RegularPart 定义；`variant` 默认 `default`。普通部件的出厂状态和实例权限继续由它的工厂决定。
- `connections` 是无向结构边列表，`a`、`b` 两端均写 `member`、`sub_part`、`connector`。顺序不表示驱动方向；创建关节时按 MM 的规则把简单端放到对接侧。单核心素体可以写空列表。
- 每个成员必须能经结构连接到达核心。允许闭环，也允许同两件部件之间有多条边，只要使用不同端点。拒绝部件间自连、不可达成员、未知型号或变体、引用内部端点、重复占点以及不兼容的连接。模型摆放后的闭合检查见 §4.2。
- 成员名仅作模板地址，实例化分配新的 UUID。素体可引用同一个部件定义多次，各次产生不同实例。

换腿接入后，腿部零件负责提供站立、蹲伏等姿态的有效支撑高度，即脚底至髋挂点的竖直距离。固定项只记录髋挂点至核心质心的竖直高差，与默认腿长独立。运行时将两项相加，再减去当前控制器中心离脚底的高度。实现接入点见 `common/part/ArmsBodyPhysics.java#bodyTarget` 的 TODO。

### 3.2 加载与失败隔离

1. BodyPart 沿用 MM 的 `parts` 装载和发布机制。共享 Codec 解码结构，定义校验检查模型、骨骼、命中框和 Locator 等引用。ARMS 的有限正质量等规则须接入发布链路：`../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/PartDefinitions.java#validateAndPublish` 调用类型的 `validate`，不能只在工厂调用时检查。
2. `arms_frames` 模块先解码并暂存同端候选。在部件发布后解析具体型号、变体与端点；可在 Spark 的整批完成事件执行这一步。事件由 `../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/pack/SparkPackLoader.kt#injectPackageContent` 在所有模块 `onFinish` 执行完毕后发送。不要以模块的注册先后作为依赖屏障。
3. 部件与素体分别按逻辑端发布。只发布通过校验的素体；诊断包含资源 ID 和成员、变体或挂点路径。无效部件和引用它的素体均不可从权威查询入口取到，也不可通过工厂创建。一个候选失败不阻止同批合法定义发布。
4. 双端走服务器内容包链路。客户端按快照中的实际部件和位姿建身体，保留素体 ID，等待本端需要的部件定义发布。服务端新建或恢复时解析素体 ID，内容包热重载与已在世实例迁移另行设计。

## 4. 实例、存档与同步

### 4.1 装配图与分裂

`common/ArmsCore.java#corePart` 保持非空 final；`common/ArmsCore.java#partMap` 保持 final 的只读视图，内容由装配操作维护。成员可为 BodyPart 或 RegularPart，所有跨成员的实际 Connector 连接都构成无向图的边。装配操作统一维护成员及连接，物理线程使用一次性发布的拓扑视图或队列中已确认的修改。

部件没有表示物理归属的 `parent` 字段，也无需 BodyAttachment。仅在首次布放时选择从核心可达的生成树；生成树是布放顺序，图中其他实际边仍建立关节。若将来做纯运动学位姿传播，可以从启用的驱动关系获得传播路径；分裂判定从完整物理图计算。

断开边后计算连通分量，包含 `corePart` 的分量保留 ArmsCore 和宿主绑定。其他分量失去原装配归属；首版在调试场景验证剥离与释放，普通残件的入世、保留与回收策略另定。不能把含 BodyPart 的残件直接交给仅接收 RegularPart 的 VehicleCore。连通性算法参考 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/VehicleCore.java#partNetSpiltCheck`；ARMS 按核心归属保留身份。首版按 Part 计算分量，内部 SubPart 关节随 Part 管理；允许内部结构单独分裂时，须另行确定 Part 如何拆分及其保存规则。

例如腿直接接在躯干上，同时经普通支架与躯干相连。断髋关节会停掉该关节的驱动，但只要支架这条路径完整，腿就留在原装配体中。切断最后一条路径时才剥离。

### 4.2 新建与恢复

| 路径 | 数据来源 | 放置与连接 |
|---|---|---|
| 从素体新建 | 已发布的素体及选定变体 | 先分配全部部件，确定核心。以核心根 SubPart 为世界锚点，沿生成树放置每个部件一次；校验所有剩余边的挂点闭合，再建立完整的外部关节集合。 |
| 从存档恢复 | 可选素体 ID、实际成员、SubPart 状态、实际连接 | 从素体定义读取控制器配置，按 UUID 恢复实际部件与状态，使用保存的关节参考系建立关节。不得以模板或安装时的对齐动作覆盖保存的世界位姿。 |

新建复用 MM 的安装坐标约定，关节位置与朝向来自两端以质心为基准的局部参考系；移动部件时保持该 Part 内各 SubPart 的相对布局。参考 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/connector/AbstractConnector.java#calculateExtraTransform`。生成树按成员名与端点名稳定排序。`common/part/ArmsBodyAssembly.java#layout` 在原点附近校验所有边的两端参考系能否以零转角闭合，随后整套平移到目标位置。树外边不会再次移动部件或改写枢轴。恢复读取原安装确定的局部参考系，当前弯曲角仍由保存的刚体位姿决定。

两条路径都先验证全部定义和拓扑，构造中途失败要清理整批未发布实例。清理要走无正常拆离掉落的路径，参考 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/AbstractPart.java#discardUnattached`。禁止将半具身体登记、绑定宿主或广播。

### 4.3 保存数据

`common/part/ArmsBodyData.java#codec` 在核心 UUID 与成员数据之外保存 `connections`，旧数据缺省为空。沿用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/data/ConnectionData.java#CODEC`：两端分别记录部件 UUID、SubPart 名、Connector 名和相对刚体质心的关节参考系；MM 的简单侧字段表示对接侧。每条实际外部边只存一次，包括闭环边。内部关节由部件变体的内部挂点构造。

成员数据按定义种类编码，允许混合 BodyPart 与 RegularPart 的存档。用 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/PartFactory.java#encodeData`、`../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/part/PartFactory.java#decodeData` 按同一逻辑端已发布的定义选择专用 Codec。网络收包阶段可以先读取 NBT，等待本端定义就绪后才解码成员。恢复校验核心必须是 BodyPart；成员列表不可被窄化为 `BodyPartData`。

缺省 `connections` 可按空集合解码；发布前须验证所有成员能到达核心，只有单核心快照允许没有边。对缺失拓扑的多部件快照报错，不推测原连接。`arms_frame` 是可选的素体资源 ID；`core_height_offset` 归素体定义。服务端恢复若找不到显式引用的素体，会在分配部件前报错。没有素体引用的身体按零偏移处理。

### 4.4 客户端组成与位姿

创建包携带核心、成员及实际连接；同一装配体的组成更新保留核心对象和成员表视图。应用时先验证整批数据，确定最终 UUID 到实例的映射，再在这些实例的 Connector 间恢复连接。任何边都不能引用用于预检的临时核心。该步骤需要调整 `common/ArmsCore.java#applyBodySnapshot`，当前入口会用临时核心校验状态，再保留原核心对象。

组成快照提供成员、连接及各 SubPart 的同步 ID 和初始位姿。`common/ArmsCore.java#enterClientBody` 把客户端运动学刚体加入物理空间，服务端负责控制器和关节求解。后续位姿沿用 MM 的 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/network/payload/SubPartSyncPayload.java#handler`，按整数 ID 寻址。目标尚未注册时直接跳过该次增量。F3+B 可检查客户端物理形状，AllBody 绘制见 §6.3。创建载荷内 NBT 的拓扑契约或载荷集合变化时，按 `network/ARMSNetwork.java#PROTOCOL_VERSION` 提升协议版本。

## 5. 被动关节物理闭环

1. **玩家场景**：`/arms spawn arms_core:allbody_joint_test` 走现有玩家绑定流程。控制器负责行走，身体核心锁到无接触响应的运动学锚点上。`common/ArmsCore.java#prePhysicsTick` 每个子步先让控制器处理输入，再调用 `common/part/ArmsBodyPhysics.java#followControllerStep` 更新锚点，随后由 Bullet 求解。`common/part/ArmsBodyPhysics.java#bodyTarget` 将身体局部 -Z 对齐到控制器局部 +Z，并沿世界 Y 轴加上 `core_height_offset`；跟随过程按位置和最短偏航误差补上限速线速度与角速度。约束反力由锚点承受，自身碰撞过滤仅针对该控制器与其身体。
2. **物理线程入世**：`common/MechaCoreRegistry.java#enterPhysicsSpace` 向 Spark 物理空间的 PRE 队列投递一个任务。任务对齐出生位姿、添加控制器、锚点与全部 SubPart，最后添加去重后的内部、外部及核心约束。入世成功后才执行宿主位姿回写，失败时回滚已经入世的对象并注销。宿主外部位移经 `common/HostPositionIntake.java#runWarpTask` 投递到同一物理队列，整套身体和控制器随之平移。
3. **碰撞规则**：地形碰撞由角色控制器承担，身体所有 SubPart 使用 `"block_collision": "false"` 排除地形碰撞组；内置定义位于 `src/main/resources/spark_modules/arms_core.builtin/arms_core/parts/`。字段与枚举见 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/attr/SubPartAttr.java#BlockCollisionType`，碰撞组配置见 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/SubPart.java#SubPart`。同装配体自碰撞由 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/SubPart.java#onPreCollision` 过滤，同机体控制器与身体之间的碰撞由 `common/part/ArmsBodyPhysics.java#enter` 排除。身体保留动态质量、重力和关节响应。
4. **退出与失败**：退出先阻止新的步进／入世任务，再解除关节并移除刚体，清理 owner、装配归属和注册表。释放幂等，涵盖构造失败、命令注销和维度卸载。若测试入口使用 `ArmsCore`，必须明确控制器是否入世，并由同一生命周期负责对称移除。现有注销入口见 `common/MechaCoreRegistry.java#removeServer`。

首个验收场景选可观察的单旋转轴：肢体因重力从偏转初态运动，受限位约束，挂点不持续漂离，外力扰动后连接仍在。随后分别加多 SubPart 手臂和 RegularPart 支架，验证内部关节与部件间关节能同时存在，以及绕行路径是否正确参与分裂判定。

## 6. 后续字段与动画接线

### 6.1 整机配置

| 扩展项 | 放置位置 | 决策前提 |
|---|---|---|
| 控制器尺寸、体型锚点、基础移动／跳跃能力 | 素体的整机配置 | 明确控制器如何承载身体、质量如何分摊，避免把同一份质量算两次。 |
| 默认动画集与整身状态映射 | 素体或其引用的共享动画配置 | 明确动画名、回退资源和表现层选择规则。单个关节用自己的 Locator 取动画目标，见 §6.2。 |
| 马达限速、力矩、刚度与阻尼 | 可复用的驱动预设，由整机配置选用 | 先测固定角度和往复目标；驱动上限与视觉混合权重各自独立。 |
| 基础能量上限、宿主基础血量 | 可选整机基础属性 | 明确部件能力如何增量合成，以及宿主生命值和部件耐久的权威边界。 |

整机配置的既有入口为 `common/control/attr/MechAttr.java#MechAttr` 存根，总体分工见 `docs/总体设计文档.md` §3.5。表中内容不进入首版 Codec。

### 6.2 用同一 Locator 的动画差值驱动关节

使用 Locator 作为动画目标时，每个关节由配置指定驱动端。直接读取该端的 `locator` 所引用的模型定位器。装配点存在或转角为零均不代表马达启用。AllBody 从共享动画的原腿骨取得目标，见 §6.3。

例如躯干的肩部 Connector 引用随 `shoulder_drive` 骨骼运动的 Locator；上臂的肘部 Connector 引用随 `elbow_drive` 骨骼运动的 Locator。驱动骨骼属于该 Connector 所在 SubPart 的骨骼范围，并能相对基准骨骼转动。若 Locator 固定在基准骨骼上，其局部变化为零。内部关节也可以按同一规则驱动，地址始终是具体 SubPart 和 Connector。

以列向量变换记号表示，`M(t)` 是同一个 Locator 在模型空间的姿态，`B(t)` 是驱动端 SubPart 的基准坐标系在同一模型空间的姿态。两者使用同一份混合姿态求值。基准可取 `start_bone` 加静止时确定的质心偏移。求得局部姿态后，先提取刚性旋转再求差：

```text
L0   = inverse(B(0)) × M(0)       # 静止模型姿态，可缓存
Lt   = inverse(B(t)) × M(t)       # 本物理步的混合动画姿态
Δloc = inverse(rotation(L0)) × rotation(Lt)
```

这里的 `0` 指无动画的模型静止姿态，不是动画片段的第一帧。驱动端整个刚体转动时，Locator 与基准一起动，这部分在局部计算中抵消。直接比较两份世界矩阵会把父件整体转动误计为目标转角。

把 `Δloc` 换算到实际安装的关节轴，按该约束的旋转顺序、符号和限位写马达目标。若 `C` 是「关节参考系 → 静止 Locator 参考系」的旋转，则目标增量是 `inverse(C) × Δloc × C`；`C` 需计入装配对齐与 MM 的关节构造约定，见 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/connector/AbstractConnector.java#attachJoint`。首版转轴只消费旋转，平移和缩放不改枢轴，`actualTransform` 保持装配时确定的参考系。关闭驱动时关节保留，肢体可受重力与外力运动。

服务端先推进动画并混合，再在同一物理子步求目标。可复用 `../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/animation/model/ModelPose.kt#getSpaceBoneLocatorMatrix` 的矩阵构造规则；现有 getter 读取的是主线程已发布的姿态。引擎在 `../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/animation/anim/AnimController.kt#physTick` 写内部姿态，在 `../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/animation/model/BonePose.kt#setChanged` 发布给渲染。物理驱动需要本步混合姿态的读口，采样不受视觉 LOD 影响；仅取目标无需引入 IK 处理器。

每条被驱动边独立取目标，物理求解器处理整张装配图，包括被动支撑边。只断开一条驱动边时停止该马达。含核心的连通分量保留在装配体中，见 §4.1 的分裂规则。

### 6.3 AllBody 的共享动画接线

无参 `/arms spawn` 使用 `arms_core:allbody_joint_test`。`common/ArmsCore.java#bodyAnimation` 在每一端持有一个 bmpt 动画体，`common/ArmsCore.java#tickBody` 在主线程推进动画 tick。`common/control/presentation/MechaAnimPresenter.java#progress` 在物理步推进现有姿态状态机、并行层和骨骼混合。`common/ArmsCore.java#prePhysicsTick` 在混合后调用服务端 `common/control/presentation/MechaBodyAnimation.java#drive`，于同一子步写入关节目标。

bmpt 片段动画化的是大腿和小腿原骨骼。绑定从连接两端的 `start_bone` 取得源骨骼路径。髋路径从 `AllBody` 下方到 `LeftLeg` 或 `RightLeg`，膝路径从大腿下方到 `LeftLowerLeg` 或 `RightLowerLeg`。路径旋转相对于静止姿态求差，再通过子零件静止骨骼、质心和实际关节 B 端参考系换轴。关节仅消费 X 旋转，马达目标按实际关节限位钳制。髋与膝的力矩上限分别为 600 N·m 和 180 N·m，追赶速度上限为 360°/s，见 `common/control/presentation/MechaBodyAnimation.java#Drive`。进入布娃娃状态时恢复动画驱动接管前的马达与弹簧参数。

客户端由 `common/part/BodyPart.java#applySharedAnimation` 按名称复制同一混合姿态的装饰骨骼增量。各 `start_bone` 及其祖先使用静止动画增量，因为该部件的整体运动已由同步的刚体位姿提供。`client/MechaPlayerRenderer.java#renderBodyParts` 使用 SubPart 的插值刚体矩阵和部件自己的模型与贴图绘制。

启动开发客户端后执行 `/arms spawn` 即可检查站立、行走、跑步和蹲伏。`src/test/java/io/github/sweetzonzi/arms_core/common/control/presentation/MechaBodyAnimationTest.java#sharedWalkClipMovesEveryHipAndKneeAlongItsBoneChain` 加载真实 bmpt 模型与 `walk` 片段，确认两髋两膝的源旋转随片段推进而变化。该测试覆盖动画取样与骨骼路径，游戏内观感由客户端验收。

## 7. 实施顺序与验收

| 阶段 | 可交付结果 | 必须验证的行为 |
|---|---|---|
| A：定义与发布 | MM 结构格式的 BodyPart、命名变体、多 SubPart、内置 MM Connector 定义、素体模块 | 单变体与单 SubPart 简写按 MM 名称解析；左右变体解析正确；共用连接标签规则生效；未知骨骼或 Locator、非法质量、无效引用都报错；失败定义和其依赖素体不能查询或创建。 |
| B：构造与恢复 | 从素体构造无向图，保存并恢复混合成员和实际连接 | 闭环和使用独立端点的平行边可往返；拒绝不闭合的挂点、重复占点、错变体和不可达成员；两次新建的 UUID 不同；缺肢与非默认变体按原样恢复；弯曲关节保留参考系与当前角度；客户端保留核心和成员表身份，所有边指向正式实例。 |
| C：被动入世 | 玩家控制器、运动学锚点和被动身体共同入世 | 已用 AllBody 覆盖移动跟随、外力扰动、膝限位与主动注销。多 SubPart 与普通支架的在世验证，以及断边后的连通分量与残件释放，仍待验收。 |
| D：可视验收 | 两端组成一致，能看到被动物理姿态 | 加入维度、延迟加载、移除与重进；姿态插值、旧包淘汰；客户端不进行重复的关节求解。 |

Codec、引用、拓扑和恢复契约走 `gradlew --offline test`。关节转角、挂点距离、能量增长等数值在靠近原点的裸 `PhysicsSpace` 内测量；GameTest 走 `gradlew --offline runGameTestServer`，验证真实装载、线程入世和生命周期，不用随机远原点的坐标量小幅抖动。该车道的坐标限制见 `docs/GameTest车道指南.md` §4、§5.2。D 阶段另用 `gradlew --offline runClient` 验证展示与重进。

阶段 C 通过即可开始固定角度驱动实验；接到正常宿主路径前，完成 D 的权威位姿验证。动画驱动的最低验收：静止姿态产生零增量，基准与 Locator 一起转动时目标不变，Locator 单独按正、负角度转动时关节沿对应轴跟随。随后量跟踪误差、阻挡后恢复及关闭驱动后的自然下垂。
