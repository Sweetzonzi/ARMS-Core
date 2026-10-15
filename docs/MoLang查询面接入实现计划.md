# MoLang 查询面接入实现计划

> 状态：**计划（未实施）**。前置：`../Spark-Core/docs/molang_compiled_path_defects_plan.md` 的 A1（编译路径除零口径）。
> 本文只做一件事：把内容包真正用到的 `query.*` 查询接到 ARMS-Core 的数据上，并让「内容包里的每条 molang 都能求出一个有限值」变成可回归的判据。
> 表现层动画本身的接入见 `docs/表现层动画接入实现计划.md`；本文是它的补丁，不改变那张图或那份映射表。

## 0. 事实基线

本节把方案依赖的事实与其证据集中列出，后面每条决策只引用本节的行。

### 0.1 引擎侧（Spark-Core）

| 事实 | 内容 | 证据 |
|------|------|------|
| 现在能用的 `q.*` 只有五个 | `anim_time`（基类）与 `all_animations_finished` / `any_animation_finished` / `lod` / `camera_distance`（`SparkMolangContext`）。另有 `SparkEntityContext` 与 `MolangLivingContext` 两批实体/生物查询（`is_sneaking`、`is_in_water`、`body_y_rotation`、`life_time` 等），但它们要求动画体是 `IEntityAnimatable`，机娘的动画体不是 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/SparkMolangContext.java#queryLod`、`../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/SparkEntityContext.java#isOnGround`、`../Spark-Core/src/main/java/cn/solarmoon/spark_core/animation/IAnimatable.kt#getMolangContext` |
| 查不到的名字静默取 0 | 编译器对未命中的命名空间或属性直接发常量 0，不抛异常、不告警。内容包里 `q.ground_speed` / `q.vertical_speed` / `ysm.*` / `ctrl.*` 因此全是 0 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/runtime/MolangCompilingVisitor.java#visitAccess` |
| `q.*` 与 `v.*` 是两套存储 | `v.*` 读写的是求值上下文自带的 `getVariableStorage()`（`SparkMolangContext` 构造时把 `IAnimatable.variables` 拷进来一次，之后两边不再同步）；`q.*` 走 `@QueryBinding` 方法 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/SparkMolangContext.java#reset`、`../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/AnimatableVariableBinding.java#get` |
| 求值上下文按类缓存引擎 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/MolangContextRegistry.java#getOrCreateEngine` 按**上下文类**建引擎，并用**无参构造**造一个原型实例来扫 `@QueryBinding` 方法 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/MolangContextRegistry.java#getOrCreateEngine` |
| 原型类的可见性有硬要求 | 自定义上下文的**类与其无参构造都必须是 `public`**，否则建原型时抛 `IllegalArgumentException: 无法创建上下文原型`。本仓库实测：包级私有的嵌套类与被包级私有类包住的 `public` 类都会失败 | 同上（异常文本由该方法抛出） |
| 绑定的方法返回 `double` | `@QueryBinding` 只接「无参、返回 `double`、非静态」的方法；`q.` 是 `query.` 的内置简写，无需额外声明 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/runtime/binding/QueryBinding.java#QueryBinding` |
| 未绑定查询在布尔上下文里会炸 | `!q.x` 与 `q.x && …` 会把子表达式按 `BOOLEAN` 期望类型编译，而未绑定查询的兜底分支发的是 `double` 常量，最终 `VerifyError: Bad type on operand stack`。绑定之后走 `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/util/AsmUtil.java#addCast` 的 `double → boolean`，类型正确 | `../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/runtime/MolangCompilingVisitor.java#visitUnary`、`../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/runtime/MolangCompilingVisitor.java#visitBinary`、`../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/util/AsmUtil.java#addCast` |
| 除零在编译路径上产出 `NaN` | `q.vertical_speed/math.abs(q.vertical_speed)` 这类式子在 `v == 0` 时是 `0.0/0.0 = NaN`（解释路径按 Molang 规定返回 0）。**只补绑定不能消掉它**：给上下文加 `vertical_speed` 绑定后，`v = 3` 得到 -22.2245…（正常），`v = 0` 仍是 `NaN` | `../Spark-Core/docs/molang_compiled_path_defects_plan.md` §1 |
| 变量缺失读 0、赋值语句可用 | `v.任意缺失名` 读回 0；`v.单级名 = 1;` 这类赋值语句求值正常并把值写进变量存储（内容包 timeline 依赖它）。**多级左值**（`v.a.b = 1;`）是另一条缺陷，见上游文档 §2 | 同上一行的文档 §2 |

### 0.2 本项目侧

| 事实 | 内容 | 证据 |
|------|------|------|
| 动画体的求值上下文是缓存的 | `MechaAnimatable` 有一个 `molangContext` 字段，构造时建一次；因为它是 `IAnimatable` 上同名方法的实现，接口默认实现（每次新建）不会生效 | `client/MechaAnimatable.java#molangContext`、`client/MechaAnimatable.java#MechaAnimatable` |
| presenter 用的就是这个上下文 | `MechaAnimPresenter` 在建立四台姿态机时取 `readyTargets.get(0).getMolangContext()` 作为 `contextProvider`，所以换掉它的实现类即可同时作用于**图条件**与**骨骼 molang** | `common/control/presentation/MechaAnimPresenter.java#molangContext`、`common/control/presentation/MechaAnimPresenter.java#startPostureMachines` |
| 客户端主线程已在读同步数据 | `client/MechaAnimatable.java#clientTick` 每 tick 调 `client/ClientMechaAnchor.java#acceptFromSyncedData`，后者就是读 `DATA_POS` / `DATA_YAW` 的地方 | `client/MechaAnimatable.java#clientTick`、`client/ClientMechaAnchor.java#acceptFromSyncedData` |
| 速度已经同步好了 | `DATA_VEL` 是 `Vector3f`，服务端每 tick 由刚体的线速度写入，单位 m/s（`DATA_POS` / `DATA_YAW` 同批） | `common/ArmsCoreServerEvents.java#syncToClients`、`common/ArmsCore.java#DATA_VEL` |
| 客户端没有宿主实体 | `ArmsCore.host` 在客户端恒为 `null`（绑定关系只在服务端），因此 `head_*_rotation` / `is_in_water` 一类查询在客户端没有现成的源头，见 §6 | `common/ArmsCore.java#host` |
| 四项逻辑状态已有下行 | 客户端主线程把 `DATA_POSTURE` / `DATA_GAIT` / `DATA_VERTICAL` / `DATA_ENERGY` 投影进 `MechaControl.variables` 的枚举与 one-hot（`IS_CROUCHING` / `IN_WATER_POSTURE` / `IS_FALLING` 等） | `common/ArmsCoreServerEvents.java#projectLogicState`、`common/control/state/MechaStateVariableKeys.java#IS_CROUCHING` |
| 变量容器归物理线程所有 | `MechaControl.variables` 由 presenter 与状态机在物理线程读写，主线程不碰；molang 求值也在物理线程，两者可以共享 | `common/control/MechaControl.java#variables`、`common/control/presentation/MechaAnimPresenter.java#progress` |

### 0.3 内容包用到的命名空间（`spark_modules/arms_core.builtin` 下 4 个动画文件）

| 命名空间 | 用到的名字（次数） | 本计划 |
|----------|--------------------|--------|
| `query.` / `q.` | `is_in_water`(133)、`vertical_speed`(82)、`ground_speed`(18)、`body_y_rotation`(10)、`yaw_speed`(6)、`head_x_rotation`(5)、`is_sneaking`(4)、`position_delta`(4)、`life_time`(2)、`head_y_rotation`(1) | **本文范围**：阶段 1 做前两个，阶段 2 做其余八个 |
| `ysm.` | `head_pitch`(556)、`head_yaw`(396)、`bone_pivot_abs`(164)、`bone_rot`(81)、`second_order`(16)、`first_order`(15)、`has_chest_plate`(4)、`has_offhand`(2)、`has_mainhand`(2)、`input_vertical`(2)、`input_horizontal`(2) | 非目标（§6） |
| `ctrl.` | 231 处（武器/炮塔本地状态） | 非目标：那是逻辑层 `ctrl.*` 设计（`docs/分层控制器与状态机设计.md` §9），要等 Part/武器接入 |
| `v.` | 1527 个变量名、5749 次 | 不接线：内容包自己的状态，缺失读 0 是合法降级 |
| `math.` | sin/abs/cos/clamp/pow/exp/pi/mod/sqrt/random/min/max | 引擎已全部提供（`pi` 是常量属性） |

### 0.4 全包实测（实施前后要看的两个数字）

用「读 4 个动画 JSON → 取出 `rotation` / `position` / `scale` / `timeline` 下的全部字符串（跳过 `lerp_mode` 这类元数据）→ 逐条编译求值」的方式量了一遍：

| 指标 | 值 |
|------|-----|
| 表达式总数 | 2323 |
| 求值得到 `NaN` | 26（全部来自除零：`jump` 6、`fly` 4、`run_jump_left` 4、`run_jump_right` 4、`gun_jump` 6、`parallel1_2` 2） |
| 抛异常 | 33（多级左值赋值 31、布尔上下文里的未绑定查询 2） |

这三类在 A1 / A2 / 本文落地后应全部归零——这就是本文阶段 3 那条用例的来处。

## 1. 目标与非目标

**目标**

1. 新建本仓库自己的 MoLang 求值上下文 `MechaMolangContext`，让内容包里的 `q.*` 落到 ARMS-Core 的真实数据上；
2. 阶段 1 先把 `ground_speed` / `vertical_speed` 接上（这两个是「空中姿态四肢消失」那条缺陷的输入，也是 `jump` / `fly` 四肢摆动、`wb`、裙摆唯一的驱动量）；
3. 把「内容包里的每条 molang 求值必须有限、且不得抛异常」固化成一条可回归的用例；
4. 阶段 2 把其余八个 `q.*` 按「来源明确、不引入跨线程读」的原则接上。

**非目标**

- `ysm.*` 与 `ctrl.*`（见 §6）；
- 内容包一个字不改（除零与多级左值都在引擎侧修，见上游文档）;
- 不改四张姿态图与映射表（`docs/表现层动画接入实现计划.md` 的范围）；
- 不引入客户端预测或本地物理：`ground_speed` / `vertical_speed` 取的是服务端权威速度的同步值。

## 2. 决策

### D1 上下文类放 `common/control/presentation/`，泛型取 `IAnimatable<ArmsCore>`

名字 `MechaMolangContext`，放表现层包（它与 `MechaAnimPresenter` 同层，被后者当 `contextProvider` 使用）。

泛型**不写** `MechaAnimatable` 而写 `IAnimatable<ArmsCore>`：`MechaAnimatable` 在 `client` 包，写它会让 `common` 反向依赖客户端；写 `IAnimatable<ArmsCore>` 之后 `getEntity().getAnimatable()` 直接得到 `ArmsCore`，无需强转，也不必 import 客户端类。Machine-Max 的 `../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/molang/MechMolangContext.java#MechMolangContext` 用的是同一手法（那边泛型取 `IAnimatable<Part>`）。

类必须是 `public` 且有无参构造（§0.1 的原型规则）。

### D2 运动量取自同步的 `DATA_VEL`，在客户端主线程发布

`ground_speed` = 水平模长 `sqrt(vx² + vz²)`，`vertical_speed` = `v.y`（正向上，与内容包 `vertical_speed>0` 的用法一致），单位 m/s。取 m/s 而不是格/tick 的理由：内容包的用法是 `math.clamp(ground_speed*3, 0, 30)` 一类**转角**（度），格/tick 下冲刺 0.216 会让转角永远小于 1°，只有 m/s 量级才落在作者想要的 0~30° 区间。

发布点放 `client/MechaAnimatable.java#clientTick`：它已经是「主线程每 tick 读一次同步数据」的位置（`anchor.acceptFromSyncedData` 就在里面），读 `core.getSyncedData().get(ArmsCore.DATA_VEL)` 不需要新的线程约定，也不需要新字段表项。

**不**在 `@QueryBinding` 方法里读 `SynchedEntityData`：同步数据容器只在主线程读，物理线程求值直接读它是跨线程读一个非线程安全结构。

### D3 值存上下文自己的 volatile 字段，方法只读字段

`MechaMolangContext` 持两个 `volatile float`，由 `setMotion(float groundSpeed, float verticalSpeed)` 写（主线程），`@QueryBinding` 方法读（物理线程）。单写单读、值本身是数字，与 `MechaControl` 既有的 `volatile` 输入字段同一套约定。

**不**写进 `IAnimatable.variables`：那份映射与上下文的 `getVariableStorage()` 只在构造时同步一次（§0.1），写它不会影响 `q.*` 的求值，反而会让人以为 `v.ground_speed` 也通了。

### D4 除零不在本仓库绕

内容包里 30 处 `query.vertical_speed/math.abs(query.vertical_speed)` 一个字不改：口径由 `../Spark-Core` 的 A1 修正（除零返回 0）。本仓库这边只负责把 `vertical_speed` 喂成真实值，以及用阶段 3 的用例证明整包有限。

### D5 `ysm.*` 与 `ctrl.*` 不在本文

见 §6。两者的共同点是「需要引擎侧的新查询面」（骨骼枢轴/旋转读取、二阶滤波、或 Part 化之后的本地状态），不是随手能填的常量。

### D6 判据是一条全量用例，不是人工看

阶段 3 的用例把内容包全部 2323 条表达式在 `MechaMolangContext` 上求值一遍，断言「全部有限、全部不抛」。它是本次缺陷的回归闸门，也是将来换内容包时的体检。

## 3. 详细设计

### 3.1 `MechaMolangContext`（新增文件）

```
public class MechaMolangContext extends SparkMolangContext<IAnimatable<ArmsCore>> {

    /** 水平速度 (m/s)，客户端主线程发布、物理线程读取 */
    private volatile float groundSpeed;
    /** 垂直速度 (m/s，正=上升) */
    private volatile float verticalSpeed;

    /** 无参构造：编译器建原型实例用（必须 public） */
    public MechaMolangContext() {
    }

    /** 绑定动画体 */
    public MechaMolangContext(IAnimatable<ArmsCore> animatable) {
        super(animatable);
    }

    /** 客户端主线程每 tick 发布一次 */
    public void setMotion(float groundSpeed, float verticalSpeed) {
        this.groundSpeed = groundSpeed;
        this.verticalSpeed = verticalSpeed;
    }

    @QueryBinding("ground_speed")
    public double queryGroundSpeed() {
        return groundSpeed;
    }

    @QueryBinding("vertical_speed")
    public double queryVerticalSpeed() {
        return verticalSpeed;
    }
}
```

要点：两个字段与两个方法都在类上，没有静态状态（引擎按类缓存原型，但求值发生在**上下文实例**上，所以实例字段是正确的载体）。

### 3.2 `MechaAnimatable` 的接线

| 位置 | 改动 |
|------|------|
| 字段 `molangContext` | 类型由 `SparkMolangContext<MechaAnimatable>` 改为 `MechaMolangContext`（`Getter` 生成的方法仍然满足 `IAnimatable` 上的 `getMolangContext()`） |
| 构造 | `this.molangContext = new MechaMolangContext(this);` |
| `clientTick` | 在 `anchor.acceptFromSyncedData` 之后读一次 `DATA_VEL`，算水平模长与 y 分量，调 `molangContext.setMotion(groundSpeed, verticalSpeed)` |

速度读数的写法（不变更任何载荷与字段表）：

```
Vector3f velocity = animatable.getSyncedData().get(ArmsCore.DATA_VEL);
float ground = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
molangContext.setMotion(ground, velocity.y);
```

### 3.3 presenter 不受影响

`MechaAnimPresenter` 只调 `../Spark-Core/src/main/java/cn/solarmoon/spark_core/animation/IAnimatable.kt#getMolangContext`（`common/control/presentation/MechaAnimPresenter.java#molangContext`），拿到的对象换了实现类，调用点与四台机器的构造参数都不用改。姿态图的变量条件读的是 `StateVariableContainer`（与 molang 无关），因此图的行为不变。

### 3.4 单位与符号的口径

| 量 | 定义 | 依据 |
|----|------|------|
| `ground_speed` | `sqrt(vx² + vz²)`，m/s，恒非负 | 内容包把它当「转身/摆臂的强度」用（`math.clamp(ground_speed*3, 0, 30)`），不需要方向 |
| `vertical_speed` | `v.y`，m/s，正=上升 | 内容包写 `vertical_speed>0 ? … : …`（`wb`）与 `q.vertical_speed/math.abs(q.vertical_speed)`（符号函数），方向必须保留 |

## 4. 实施阶段

### 阶段 0：盘点（已完成，结论即 §0.3 与 §0.4）

| 项 | 内容 |
|----|------|
| 改动 | 无（本阶段只做只读盘点，不落地代码） |
| 产出 | 内容包用到的命名空间与名字清单；全包 2323 条表达式的 NaN / 异常清单；未绑定查询在布尔上下文抛 `VerifyError` 的证据（见上游文档 §3） |
| 验收 | 清单能解释用户报告的现象：`jump` / `fly` 的四肢骨骼命中 26 条 `NaN` 中的 10 条，其余骨骼不含该除法 |

### 阶段 1：上下文与两个运动量

| 项 | 内容 |
|----|------|
| 改动 | 新增 `common/control/presentation/MechaMolangContext.java`；`client/MechaAnimatable.java` 换上下文类型并在 `clientTick` 里发布速度 |
| 验收 | 单元：`q.ground_speed` / `q.vertical_speed` 在给定上下文后读回设定值；未设定时读 0；`jump` / `fly` 的原式在 `v = 0` 与 `v = 3` 下均有限（依赖上游 A1）。实机：进入空中姿态四肢正常显示，且摆臂幅度随上升/下落速度变化 |
| 落地位置 | `common/control/presentation/MechaMolangContext.java`（新增）、`client/MechaAnimatable.java#molangContext`、`client/MechaAnimatable.java#clientTick` |

### 阶段 2：其余八个 `q.*`

按「来源是否现成、是否需要新接线」分两批，逐名给出来源与判据：

| 查询名 | 用法次数 | 来源 | 接线 |
|--------|---------:|------|------|
| `is_in_water` | 133 | `common/control/state/MechaStateVariableKeys.java#IN_WATER_POSTURE`（下行 one-hot） | 需要 presenter 把 `MechaControl.variables` 交给上下文（新增 `common/control/presentation/MechaMolangContext.java` 的 `attachVariables(StateVariableContainer)`，由 `MechaAnimPresenter` 在建立机器前调一次） |
| `is_sneaking` | 4 | `common/control/state/MechaStateVariableKeys.java#IS_CROUCHING` | 同 `is_in_water`（presenter 注入变量容器） |
| `body_y_rotation` | 10 | 同步 `DATA_YAW` 的 y（度） | 与 `setMotion` 同批在 `clientTick` 发布 |
| `yaw_speed` | 6 | `DATA_YAW` 的 y 对时间的差分（度/秒），一阶滤波 | 同 `body_y_rotation`（并保存上一次的 yaw 与 tick） |
| `head_x_rotation` | 5 | 宿主实体的头部俯仰（度） | 只在渲染期拿得到宿主实体（`client/MechaPlayerRenderer.java#onRenderPlayerPre`）；由渲染路径发布 |
| `head_y_rotation` | 1 | 宿主实体的头部偏航（度） | 同 `head_x_rotation`（渲染路径发布） |
| `position_delta` | 4 | **待定**：先按 `DATA_POS` 的每 tick 位移（m/s 矢量）实现，再从具体用法反推是否需要局部坐标系 | 阶段 0 的清单里没有它的语义证据，落地前先在实机上确认这两个动画的意图 |
| `life_time` | 2 | 动画体创建以来的秒数 | `MechaAnimatable` 自持一个计数器，`clientTick` 里更新后发布 |

验收：同一批表达式在绑定上下文上全部求值成功（不抛异常）；`is_in_water` / `is_sneaking` 在对应状态下取 1、其余取 0（用调试打印比对 `variables` 的 one-hot）。

落地位置：`common/control/presentation/MechaMolangContext.java`、`common/control/presentation/MechaAnimPresenter.java`（注入变量容器）、`client/MechaAnimatable.java`、`client/MechaPlayerRenderer.java`。

### 阶段 3：全包有限性用例（回归闸门）

| 项 | 内容 |
|----|------|
| 改动 | 新增 `src/test/java/io/github/sweetzonzi/arms_core/common/control/presentation/MechaPackMolangTest.java` |
| 做法 | 用 Gson 解析 4 个动画 JSON（MC 依赖里自带 `com.google.gson`），遍历 `animations.*.bones.*.{rotation,position,scale}` 与 `animations.*.timeline`，跳过 `lerp_mode` 元数据，取出全部字符串，在**绑定上下文**（`MechaMolangContext` + 阶段 1/2 的发布值，取默认 0）上逐条编译求值 |
| 断言 | ① 一条都不抛异常；② 结果是有限数（`!Double.isNaN(v) && !Double.isInfinite(v)`）；③ 表达式总数不少于 2323（防止 JSON 结构变化让用例悄悄变空跑） |
| 依赖 | 上游 A1 与 A2：不修 A1 时 26 条是 `NaN`；不修 A2 时 31 条抛 `VerifyError`。因此本条用例通过等于上游两项修正在本内容包上的验收 |
| 落地位置 | 用例文件本身；证据数字见 §0.4 |

### 阶段 4：文档与注释同步

| 项 | 内容 |
|----|------|
| 改动 | `common/control/presentation/MechaMolangContext.java` 的类注释（值的来源、线程约定、为何不读同步容器）；`client/MechaAnimatable.java` 的 `molangContext` 字段注释；`AGENTS.md` 的 `common/control/presentation/` 行与文档参考表；`docs/分层控制器与状态机设计.md` 的 MoLang 一节（记录 `q.*` 查询面已落地的那部分，与 `ctrl.*` 仍未落地分开写） |
| 验收 | `pwsh -NoProfile -File tools/check_doc_refs.ps1` 退出码 0；按 `AGENTS.md` 的文档编辑规范做一次触发词扫描 |

## 5. 验证方式

| 车道 | 能覆盖 | 覆盖不了 |
|------|--------|----------|
| 单元（`./gradlew --offline test`） | 绑定取值、未设定时的默认值、全包 2323 条表达式的有限性与不抛 | 值是否与真实运动一致 |
| 实机（`runClient`） | 空中姿态四肢是否显示、摆臂/裙摆是否随速度变化、入水与蹲伏的查询是否取到 1 | 数值标定（`*3` 这类系数是内容侧的手感，需要人眼） |
| 服务端（`runServer` / `GameTestServer`） | 服务端不因新增上下文而改变行为（服务端不注入动画目标，`progress` 空转） | 客户端表现 |

实机判据（阶段 1 完成即可看）：

1. 进入空中姿态后四肢**完整绘制**；
2. 上升与下落时四肢的摆角方向相反（内容包用 `vertical_speed` 的符号）；
3. 站立时四肢停在常数角（`vertical_speed = 0` 的那一支），不出现整块缺失。

## 6. 已知缺口与后续

| 缺口 | 内容 | 为什么不在本文 |
|------|------|----------------|
| `ysm.*`（1240 处） | `head_pitch` / `head_yaw` / `bone_rot` / `bone_pivot_abs` 需要按骨骼名读当前姿态的枢轴与旋转；`first_order` / `second_order` 是二阶滤波函数；`input_vertical` / `input_horizontal` 可以直接取 `../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/state_machine/presets/StateVariableKeys.kt#INPUT_FORWARD` / 侧移输入；`has_offhand` / `has_mainhand` / `has_chest_plate` 是装配体装备状态 | 这是**引擎侧的新查询面**（要在求值上下文里访问 `ModelController` 的骨骼姿态与滤波状态），需要单独的设计与线程约定；它影响的是 `pre_parallel*` 与物理模拟层（`docs/表现层动画接入实现计划.md` §8.2 已列为缺口），不影响本期姿态选片 |
| `ctrl.*`（231 处） | 武器/炮塔本地状态机 | 属逻辑层设计（`docs/分层控制器与状态机设计.md` §9），要等 Part 与武器接入 |
| `parallel1_1` / `parallel1_2` 的物理模拟链路 | 这两层是内容包自带的二阶物理模拟层，写 `v.*` 状态并在 timeline 里做积分；它们的除数（`v.h2F_jx` 等）默认 0，A1 之后得到 0 而不是 `NaN` | 层本身尚未接入（`docs/表现层动画接入实现计划.md` §8.2） |
| `position_delta` 的语义 | 见阶段 2 表 | 缺语义证据，落地前需实机确认 |

## 7. 风险

| 项 | 评估 |
|----|------|
| 依赖上游 | 阶段 1 的用例依赖 `../Spark-Core` 的 A1；A2 决定阶段 3 能否全绿。上游先说好再做，避免本仓库临时在内容包侧绕 |
| 单位口径 | `*3` / `*4` / `*5` 是内容侧手感系数，若实机看起来过冲或不动，改的是这些系数而不是 `m/s` 的定义（`§3.4` 给了为什么取 m/s） |
| 跨线程 | 只用「主线程写 volatile 字段、物理线程读」与「物理线程独占的变量容器」两种约定；**不**在评分线程读 `SynchedEntityData` |
| 上下文类换掉 | 引擎按上下文类缓存已编译表达式，换类会重新编译一遍（一次性开销）；已经编译过的表达式字符串不受影响 |
| 全量用例的脆弱性 | JSON 结构变化会让遍历取不到东西，因此断言里带「表达式总数下限」，取不到就会失败而不是静默通过 |
