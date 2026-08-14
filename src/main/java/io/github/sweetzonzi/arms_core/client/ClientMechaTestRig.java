package io.github.sweetzonzi.arms_core.client;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.control.MechaControlHolder;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import lombok.Getter;

/**
 * 客户端单玩家测试夹具 —— 最小 {@link MechaControlHolder} 实现。
 * <p>
 * 目的（见 docs/下一步开发TODO.md §6）：让 {@link ARMSClient} 走完整链路
 * {@code 输入采集 → MechaControl → 状态机 → KCC}，而不是直接持有裸 KCC。
 * 不使用尚未完成且会传入 null KCC 的正式 {@code ArmsCore} 骨架。
 * <p>
 * 正式运行时应由每个 {@code ArmsCore} 或 {@code MechControllerSubsystem}
 * 分别持有自己的 MechaControl，本类仅用于客户端测试闭环。
 *
 * @author Sweetzonzi
 */
@Getter
public class ClientMechaTestRig implements MechaControlHolder {

    /** KCC 运动学胶囊控制器（由 MechaControl 内部持有并驱动） */
    private final MechaCharacter kcc;

    /** 运动控制编排器 */
    private final MechaControl mechaControl;

    public ClientMechaTestRig(CapsuleCollisionShape shape, PhysicsSpace physicsSpace) {
        this.kcc = new MechaCharacter(shape, physicsSpace);
        this.mechaControl = new MechaControl(this, kcc);
    }

    /** 测试夹具无装配体，返回 null（MechaControl 当前不查询根 SubPart）。 */
    @Override
    public SubPart getRootSubPart() {
        return null;
    }

    /** 测试夹具无素体定义元数据，返回空存根。 */
    @Override
    public MechAttr getAttr() {
        return new MechAttr();
    }
}
