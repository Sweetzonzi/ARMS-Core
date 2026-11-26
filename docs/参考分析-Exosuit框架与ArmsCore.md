# Exosuit Framework 能给 ARMS-Core 带来什么

> **v2 · 2026-11-16 · 视角修正版**
> v1 误把 RimWorld 侧的空壳 `MyMod` 当成"我们的项目"，整篇写成了「怎么在 RimWorld 落地 ARMS-Core 概念」。现更正：**主体是 ARMS-Core（Minecraft 1.21.1 / NeoForge），RimWorld 的 Exosuit Framework 只是另一个游戏的第三方模组，用作跨游戏设计参考，不是落地点。**
> 阅读约定：本文涉及 RimWorld 的名词都随文给一句人话解释，不要求读者了解该游戏或其模组生态。

---

## 0. 关系与结论

| 项目 | 身份 |
|---|---|
| **ARMS-Core** | 我们的当前项目，Machine-Max 的附属模组（`AGENTS.md` 自述），机娘/机甲角色控制器 |
| **MachineMax** | ARMS-Core 的前置（复合构建依赖 `machine_max_version=1.0.3-beta.6`），提供 Part / SubPart / Connector / Subsystem / 内容包体系 |
| **Exosuit Framework** | 第三方作者 AobaKuma 的 **RimWorld** 模组（`AobaKuma/MechsuitFramework`，工坊 ID 3352894993）。纯设计参考，与我们的代码库无技术关系 |
| `MyMod` | RimWorld 侧的空壳工程，与本任务无关 |

**一句话结论**：两个项目处在互补的完成度剖面 —— Exosuit Framework 的**技术底层很轻**（把机甲伪装成"衣服"，借原版系统），但**玩法闭环非常完整**；ARMS-Core 的**技术底层极深**（异步物理、宿主 Mixin、双端权威、Codec 存档、内容包、三条测试车道），但**玩法层大多还停留在设计文档里**。所以值得看的是「怎么把机甲变成一套可玩的循环」，不是「怎么实现物理」。

**许可**：Exosuit Framework 本地仓库 HEAD 无 LICENSE 文件，按默认「保留所有权利」处理；MachineMax GPLv3、ARMS-Core LGPL-3.0。本文只做设计层参考，不涉及代码复用（C# ↔ Java/Kotlin 本就不通）。

---

## 1. 完成度剖面对照

| 维度 | Exosuit Framework 已实现 | ARMS-Core 现状 |
|---|---|---|
| 运动 / 物理 | 无（移速就是一个数值属性） | 极深：`MechaControl` / `MechaCharacter` 刚体 / 力速曲线 / 地形拼缝修正 / 逻辑状态机 |
| 宿主接入 | 借用原版 Apparel，靠 20+ 补丁兜住原版行为 | 极深：`IArmsHost` + `PlayerHostMixin` + 位置摄入 + 双端权威 + 伤害解析 |
| 装配拓扑 | 固定 7 个槽位（`SlotDef`） | 自由图拓扑：Connector / 关节 / `ArmsFrame` 连接列表 / 图分裂 |
| 存档 | `ExposeData` + 后向兼容转换链 | `BodyPartData` / `ArmsBodyData` Codec，`applyBodySnapshot` 先校验后应用 |
| **改装设施** | **维护舱：虚拟展示人、改装队列、上下机、自动维修、补给架、泊位分配、作息表** | 培养罐只在 `总体设计文档.md §3.4` 有一段流程描述；`BodyPart 最小接入.md` 明确「暂不引入换装 UI、游戏内实时换装、残件玩法、伤害转发」 |
| **维修 / 残骸 / 回收** | **完整闭环**：模块耐久、自动维修臂、毁机爆炸生成残骸、驾驶员被收容、模块概率掉落 | 设计文档 §6.3 / §7 有设想，代码未实现；`BodyPart#permitsRepair()` 当前恒返回 `false` |
| **维护与 NPC 生态** | 殖民者 AI 自动执行维护工作；敌人生成阶段按派系注入机甲 | 设计文档 §6「预装配机甲敌人」为设想，未实现 |
| 内容分层 | 框架不带成品机体，由下游模组提供 | 只有单个 `arms_core.builtin` 包（MachineMax 侧已有 builtin→型谱→整车三层） |

> 结论：**不要向它学技术，向它学"把机甲放进世界里过日子"这件事。** 下面按优先级给可落地的建议，每条都标了 ARMS-Core 里的落点。

---

## 2. 可借鉴清单（按优先级）

### A. 维护舱 → 培养罐：把「切换素体」做成设施级玩法闭环 【最高优先级】

Exosuit 的 `Building_MaintenanceBay`（约 1400 行）不是"一个改装 GUI"，而是一整套让机体在世界中有存在感的设施：

- **虚拟展示人（Dummy）**：机架上用一个假 Pawn 展示停放的机体，玩家在假人上预览改装，确认后 `GearUp()` 一次性把模块装备到真 Pawn，`GearDown()` 反向搬回。
- **改装事务队列 `ModuleWorkRequest`**：支持「先拆后装」、跨机架的模块预留（`IsModuleReservedByAnyGantry`）、冲突取消、`CalculateProjectedLoad()` 预测换装后是否超载；请求本身可序列化，读档后能恢复。
- **上机/下机流程**与「机体停在架上 / 挂在人身上」的状态机；泊位归属、作息表决定何时穿机甲。
- **自动维修臂 + 补给架 + 储物扫描**：`CompModuleStorage` 扫相邻货架里待修/待装填的模块，殖民者自动去修、去装弹、去回库。
- **毁机 → 残骸 → 回收**：`Building_Wreckage` 把驾驶员收容进去，模块按概率可回收。

**ARMS-Core 的对应落点**：

1. `总体设计文档.md §3.4` 的培养罐流程目前只有「提交改装方案 → 主线程生成结构修改命令 → 物理线程重建刚体 → 回传确认」。**改装天然是异步事务**（要卸旧刚体、重建约束），正好需要 Exosuit 那种「可排队、可取消、有预演」的请求模型。预演可以直接复用 `ArmsBodyAssembly#restore` 已有的「先恢复到临时实例完成校验，再应用」模式。
2. **虚拟宿主预览**与 `IArmsHost` 的「宿主形态可替换」天然契合（玩家 / Doll / AI 敌人都是宿主）；也能替代当前 `MechaPlayerRenderer` 的占位整模，让培养罐里看到真实素体。
3. **上机/下机 = 绑定/解绑的状态机**，应与 `宿主接入与伤害管线设计.md §3.1.3` 的死亡三档规则（残骸 / 同维度保留 / 注销重建）统一成一张生命周期表；目前 `PlayerEvent#Clone` 尚未订阅，三档全未实现（该文档 §九已登记）。
4. **维修是明确缺口**：`BodyPart#permitsRepair()` 恒 `false`、`repairLimitRatio()=1f`。Exosuit 把「维修」做成设施 + 任务 + 补给链，而我们的设计文档 §5.2 还把「定期维护」写成好感度来源之一 —— 这两处可以一起设计。
5. **残骸 → 回收 → 再制造**闭环（设计文档 §6.3、§7.1 已有设想），Exosuit 给了一个可玩的落地形态参考。

### B. 形态切换时的状态迁移契约与扩展点

Exosuit 里每个模块有「地上物品 / 身上装备」两套定义，`MechUtility.Conversion()` 用 `MechData` 搬运品质、颜色、耐久（还按品质系数换算）、剩余弹药、燃料；**关键设计是 `IModuleDataTransfer` 接口 + `OnInitData` / `OnRestoreToItem` / `OnRestoreToMech` 三个静态回调** —— 附属模组不用改框架代码就能让自己部件上的自定义数据跟着一起迁移。加载期另有 `ConfigErrors()` 校验槽位配置。

**ARMS-Core 已有且更严谨**：`BodyPartData`（variant、`shared_durability_ratio`、`texture_name`、SubPart 状态）、`ArmsBodyData`（成员列表 + 实际连接 + 可选 `arms_frame`）、`applyBodySnapshot`（整批先恢复到临时实例校验、核心身份不符则整批拒绝）、创建包同步组成 NBT。

**建议补两点**：

- 一个**对外可扩展的迁移接口/回调**：内容包作者或附属模组给自己的 Part 挂自定义状态（弹药、能量、涂装、Buff、子系统运行态）时，不必改 `ArmsCore` 的序列化代码。当前 Codec 是封闭的 record 字段，这是生态扩展的瓶颈。
- 明确**状态换算语义**：`shared_durability_ratio` 是比例而不是绝对值，换到不同最大耐久/品质的部件、或切换素体时，哪些状态保留、按什么基准换算、哪些必须丢弃，需要写成契约（Exosuit 用品质系数做 HP 换算，是一个可对照的简化例）。

### C. 面向内容包作者的约束表达与加载期校验

Exosuit 用 `SlotDef` + `CompProperties_ExosuitModule` 的 `occupiedSlots` / `disabledSlots` / `supportedSlots` / `uiPriority` 表达「这个模块占哪些槽、装了核心后开放哪些新槽、哪些槽互斥、UI 上排第几」，并在 `ConfigErrors()` 里一次性报出「没配槽」「槽位 UI 优先级重复」。

**ARMS-Core 已有**：`ArmsFrame.CODEC` 的 `validate`、`ArmsFrameModule#publish` 的引用校验、`PackCodec` 诊断，以及 MachineMax 的 `checkContent` 构建期扫描。

**建议**：在 `ArmsFrame` / `BodyPartType` 上补一层**面向玩家与 UGC 的语义约束** —— 素体可声明「启用/禁用哪些挂点」、一个部件可占用多个挂点、变体互斥规则、改装 UI 的布局优先级，并在 `publish` 阶段集中报全部错误。
⚠️ **不要把自由拓扑降级成固定槽位**：Connector / 关节 / 图分裂是 ARMS-Core 相对它的核心优势，固定槽是它在没有刚体时的妥协。这里借鉴的只是「把复杂拓扑翻译成玩家能理解、作者能校验的约束」。

### D. 模块化 UI / HUD 扩展点

Exosuit 定义 `IModuleExtensionUI` / `IModuleTopExtensionUI`，每个模块可以自带面板挂到主改装界面；`Gizmo_*` 是选中单位后的按钮（燃料、双武器切换、炮塔弹药）。

**结合 MachineMax 生态**：MachineMax 内容包已有 AUI 页面、`huds` 样式、`hud_layouts` 布局、`documents` 说明页。建议在培养罐与机体 HUD 上提供同类扩展点，让 **Part 定义自己声明「我要挂哪个面板」**，而不是把 UI 写死在培养罐方块里 —— 这与你们的数据驱动理念一致，也能让内容包作者给自定义部件做专属界面。

### E. 玩家可读的状态与来源明细

Exosuit 的 `StatPart.ExplanationPart` 把每一条属性加成的来源逐行列给玩家；并用一句能讲清的规则做惩罚：「承载 < 自重 → 超载 → 移速减半」。

ARMS-Core 的 `ModdableAttributeManager`（ADDITIVE → MULTIPLICATIVE → FINAL_MULTIPLIER 三阶段 + 按 source 追溯/移除）**技术上比它强得多**，缺的是展示层。建议做一份「机体状态摘要」：载荷 / 功率余量 / 部件健康度 / 每条加成的来源与阶段。尤其 ARMS-Core 有真实质量分布与力速曲线，玩家更需要一句能读懂的解释，而不是一堆物理量。

### F. 「借用原版宿主」的兼容性回归清单 【最实用、可直接产出】

Exosuit 把机甲做成"衣服"省了新实体，代价是 20+ 个 Harmony 补丁（≈ MC 的 Mixin）。**这份补丁清单的真正价值，是一张「原版有哪些系统会因为宿主变成机甲而行为异常」的检查表**：

| 它的补丁类别 | 处理的原版系统 |
|---|---|
| 生活逻辑 | 床位/睡眠、救援、尸体剥离、可燃性、休息与工作优先级 |
| 装备与生成 | 手持物检查、掉落、NPC 生成阶段的装备注入、按派系随机化 |
| 渲染 | 渲染树、挂点偏移、武器绘制位置 |
| 生态 | DLC 规则、时间分配 UI、多人 API |

ARMS-Core 已经处理了一批：飞行许可（`CREATIVE_FLIGHT` 修饰符）、冲刺旗标 + 视野修饰符幂等、位置摄入与 `/tp`、伤害解析、双端同步、死亡三档、`noPhysics`。

**建议产出一页《宿主兼容矩阵》**，把上表翻译成 MC 语境逐项自查，至少覆盖：床/睡眠与幻翼、骑乘与座位互斥（`总体设计文档.md §2.5.3` 的「外骨骼坐高达」）、传送/末影珍珠、火焰与可燃性、死亡掉落与 `KeepInventory`、附魔/护甲/药水、怪物 AI 与村民对宿主的判定、`/kill` 与旁观模式、第三方模组对 `Attributes` 的修改、维度切换与断线重连、旁观者同步。每项标注「已处理 / 待验证 / 不适用」+ 对应文档坐标。这是本次参考里最容易立刻变成资产的一项。

### G. 框架 / 内容分层、可选集成、存档演进

- **纯框架 + 内容包**：Exosuit 自己几乎不带成品机体，机体由下游模组提供；用 `LoadFolders.xml` 按游戏版本与「某模组是否启用」条件加载目录，把 Combat Extended / HAR 兼容做成**独立程序集**，主代码里不散落 `if (modLoaded)`。ARMS-Core 目前只有一个 `arms_core.builtin`（内含 parts / connectors / arms_frames），而 MachineMax 已有 builtin → 型谱包 → 整车包三层。建议明确：框架仓库只留机制 + 一个最小验证包，成品素体/机娘拆成独立内容包。
- **存档后向兼容**：Exosuit 把 `BackCompat_Exosuit_1_6` 挂进 `BackCompatibility.conversionChain` 处理旧存档。ARMS-Core 目前「登录不做正反序列化，重启后人类形态」（`宿主接入与伤害管线设计.md §3.1.3`），但 `ArmsFrame` / `BodyPartType` 的 Codec 已经承担定义校验 —— 现在就给素体定义留 `version` + 转换链，未来素体 ID 变更、部件变体重命名、连接结构升级会省很多事。

### H. 敌人生成阶段的注入与资源循环

Exosuit 用 `PawnGenerator.GenerateGearFor` / `Faction.RandomPawnKind` 让 NPC 在生成阶段按派系随机获得机甲。对应 `总体设计文档.md §6` 的「预装配机甲敌人」：可借鉴「按派系/难度注入预装配蓝图」的做法，并与 §6.3 的残骸掉落、可回收零件一起做成配置化的资源循环，而不是硬编码刷怪逻辑。

---

## 3. 明确不要借鉴

| 它的做法 | 为什么不适合 ARMS-Core |
|---|---|
| 固定 7 槽位替代装配图 | 自由拓扑 + 关节 + 图分裂是我们更强的部分，照搬是倒退 |
| 「整机 HP = 各模块 HP 之和」+ 伤害随机分摊给模块 | 我们有精确命中、按 Part 耐久、部位破坏与图分裂，方向更优（且方向相反：我们是「命中具体 Part → 转发宿主」） |
| 用数值属性表达运动 | `MechaControl` 与异步物理是本项目的核心卖点 |
| Apparel 伪装、Harmony 补丁、Def XML、Job/WorkGiver | 纯 RimWorld 平台实现，与 NeoForge 无关 |
| 复制源码 | 语言不同，且该仓库无 LICENSE；MachineMax / ARMS-Core 自身许可也需遵守 |

---

## 4. 建议的下一步（都在 ARMS-Core 仓库里）

1. **产出《宿主兼容矩阵》**（§F）—— 一页文档 + 一份回归测试清单，成本最低、收益最直接。
2. **补一份《培养罐与改装设施设计》**，扩展 `总体设计文档.md §3.4`：定义改装事务（排队/取消/预演/预测载荷）、虚拟展示宿主、上机下机状态机、维修与补给链、残骸回收；与 `宿主接入与伤害管线设计.md §3.1.3` 的生命周期三档对齐。
3. **给 `ArmsFrame` 的 `publish` 阶段加语义约束校验**（§C），并给 Part 状态迁移加对外扩展接口（§B）。
4. **内容包分层调整**（§G）：框架仓库瘦身，成品素体外移。
5. 需要的话，我可以直接起草 1 或 2 的文档骨架，并按你们 `docs/` 现有的自包含写法（引用按 `路径#符号` 给坐标）来写。

## 5. 附录：组装逻辑与槽位机制（源码级）

### 5.1 定义层：槽是 Def，模块声明占槽

`Defs/SlotDef.cs` —— 自定义 Def 类型，只有四个字段：

| 字段 | 含义 |
|---|---|
| `isCoreFrame` | 是否核心槽（"用来显示的 CoreFrame 只会有一个"） |
| `isWeapon` | 是否武器槽 |
| `supportedSlots: List<SlotDef>` | **装上这个槽后开放哪些子槽**（树形扩展点） |
| `uiPriority: int` | 在改装 UI 里占第几格 |

`Comp/CompSuitModule.cs` 的 `CompProperties_ExosuitModule`：

| 字段 | 含义 |
|---|---|
| `EquipedThingDef` / `ItemDef` | 模块的两套形态（身上装备 / 地上物品） |
| `occupiedSlots: List<SlotDef>` | **可占多个槽** |
| `disabledSlots: List<SlotDef>` | 核心用它禁用某些槽 |
| `repairEfficiency` | 作为物品被修理的效率 |
| `ConfigErrors()` | 加载期校验：至少占一个槽；同一模块的多个槽不能撞 `uiPriority` |

`1.6/Defs/SlotDef.xml` 内置 7 个槽，本质是「一棵两层树」：`Core`(uiPriority 0, isCoreFrame) 的 `supportedSlots` 列出 `Head` / `Attachment` / `ArmLeft` / `ArmRight` / `MountLeft` / `MountRight`（分别占 1–6 格），其余 6 个槽都继承 `BaseSlot`，`supportedSlots` 为空。内置模块里只有燃料电池用到 `occupiedSlots`（占 `Attachment` 一个槽），见 `1.6/Defs/FuelCellDefs.xml`。

### 5.2 运行时：槽位状态是「从穿着列表派生出来的缓存」

`Building_MaintenanceBay.TryUpdateCache()` 扫描虚拟展示人 `Dummy.apparel.WornApparel`，对每个模块的 `occupiedSlots` 写入两张表：

- `occupiedSlots: Dictionary<SlotDef, Thing>` —— 槽 → 当前装的模块
- `positionWSlot: Dictionary<int, SlotDef>` —— uiPriority → 槽（只给 UI 反查用）

`ComponentsCache` 收所有模块 Comp，`Core` 单独记一个 `Exosuit_Core`。**槽位状态本身不进存档**：存档里只有"Dummy 穿着哪些 Apparel"，读档/改装后 `SetCacheDirty()` → 重新扫描重建。这是一个很省事的设计，可以避免槽位与模块两份状态不一致。

### 5.3「允许任意多的可扩展插槽吗」——分三层回答

**✅ 数据层：槽位数量开放。** 加槽只要写一段 `Exosuit.SlotDef` XML，不需要改 C#；字典没有容量上限（`positionWSlot = new(7)` 只是初始容量，不是限制）。`StatWorker_SlotUsers` 会自动把"哪些模块能装到这个槽"列到信息卡上。

**⚠️ 树形扩展：`supportedSlots` 只有一半能力。**
- 递归确实存在：`GetSupportedSlotRecur()` 会遍历整棵子槽树，`RemoveModules(slot)` 用它做**级联卸载** —— 拆掉父槽时，把它开放的所有后代槽上的模块一并卸下。
- 但**安装与 UI 只认第一层**：换核心时 `RequestInstallModule` 只把 `newCoreSlot.supportedSlots` 的第一层塞进 `newCoreSupportedSlots`；`ITab_Exosuit.Draw_GizmoSlot()` 反查槽位也只在 `PositionWSlot[0].supportedSlots.Find(...)` 第一层里找。第二层以上的子槽装不上、也画不出来。
- 而且「新核心能开哪些槽」由**核心所占的那个 SlotDef** 决定，不是模块自己声明。所有核心若共用同一个 `Core` 槽，就共享同一套 6 个子槽；想让不同核心开不同槽，需要定义多个 `isCoreFrame=true` 的 SlotDef（可行），但 `MiscDefOf.Core` 与 UI 第 0 格都有硬编码假定。
- 内置内容里这个扩展能力**根本没用起来**：除 Core 外所有槽的 `supportedSlots` 都是空的。

**❌ UI 层：硬上限 7 格。** `Misc/ITab_Exosuit.cs:157` 写死 `for (int i = 0; i <= 6; i++) Draw_GizmoSlot(i);`，`positions` 静态字典只有 0–6 七个固定坐标；第 0 格固定是核心（双倍尺寸），1–3 左列、4–6 右列（`slot.uiPriority == 0 || > 3` 也是写死的）。`uiPriority` 超出 0–6 的槽画不出来，落在区间内会覆盖已有格子。**要真正"任意多槽"，必须重写这个 ITab。**

### 5.4 改装事务（`RequestInstallModule`，约 140 行）

- **装核心**：清空全部待办 → 从新核心的 SlotDef 取 `supportedSlots`、从模块取 `disabledSlots` → 若勾选「保留模块」，筛出新核心仍支持且未禁用的旧模块 → 为每个当前占用槽生成卸载请求 → 追加装核心请求 → 追加重装旧模块请求。核心工作进行中禁止插装其他模块。
- **装普通模块**：`CancelConflictingPendingInstalls()` 撤销占槽冲突的待装请求 → 对 `occupiedSlots` 里**每个槽**各生成一条 `ModuleWorkRequest`（已占则 `needRemoveFirst=true`，即先拆后装）。
- `ModuleWorkRequest : IExposable` 可序列化，字段 `targetSlot / moduleToInstall / moduleDefToReinstall / needRemoveFirst / removeCompleted`；由 `WorkGiver` + `JobDriver` 让殖民者实际走过去执行。
- **预约与冲突**：`IsModuleReservedByAnyGantry` 防止同一模块被两个机架同时预定；`GetAvailableModules(slot)` 从相邻储物架里筛出能装到该槽的模块。
- **载荷预测**：`CalculateProjectedLoad()` 用 HashSet 去重，算「当前自重 + 待装 − 待卸」给 UI 载荷条预览；`Exosuit_Core.RefreshCapacity()` 把各模块的 `equippedStatOffsets.CarryingCapacity` 汇总为承载、`Mass` 汇总为自重，`Overload => Capacity < DeadWeight`，超载时 `StatPart_MoveSpeed` 把移速 ×0.5。
- **多槽模块**：`GetModuleInfoString()` 提示"会占用哪些已用槽"；预测载荷用 HashSet 防重复计数；`ConfigErrors` 禁止同一模块的两个槽共用一个 uiPriority。
- **上下机**：`GearUp/GearDown` 在 Dummy 与真 Pawn 之间搬 Apparel，顺带处理锁定、强制武器冲突、背包物品转移。

### 5.5 对 ARMS-Core 的启示

1. **别重复它「数据层开放、UI 层写死」的架构债**：加槽零成本、加格子要改 C#。培养罐的槽位布局也应走内容包（MachineMax 已有 `hud_layouts` / AUI），不要硬编码格数或左右分栏规则。
2. **树形级联卸载的语义值得对齐**：拆父部件时自动连带处理它开放挂点上的子部件，与 `总体设计文档.md §4.2` 的图分裂 / 连通分量能接上；但要把"层级"和"连接图"分开表达，别让 UI 只认一层。
3. **`disabledSlots` 这种负向约束适合做素体变体**（轻型骨架禁用重挂点），比只写「支持哪些」灵活；可对照补到 `ArmsFrame` 的定义里。
4. **改装事务三件套**（冲突/预约、先拆后装、载荷预测）是 `ArmsFrame` 目前没有的流程层，而你们的改装要重建物理刚体，比它更需要这套事务。

---

> 待确认：这次参考是只做设计调研，还是打算近期就把培养罐/改装设施排进里程碑？若排期，上面第 2 项会与 `下一步开发TODO.md` 的 P2「状态机反向控制 KCC」、P6「宿主与同步」争用同一批改动，建议先定边界。
