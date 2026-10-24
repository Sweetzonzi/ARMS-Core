package io.github.sweetzonzi.arms_core.common.part;

import cn.solarmoon.spark_core.physics.body.PhysicsBodyExtensionKt;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPart;
import io.github.sweetzonzi.machine_max.common.mech.part.PartCreationContext;
import io.github.sweetzonzi.machine_max.common.mech.part.PartRestoreMode;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** 身体部件，持有真实子结构；占位阶段的刚体保持在物理世界之外。 */
public final class BodyPart extends AbstractPart<BodyPartType> {
    public static final PartCreationContext CONTEXT = new PartCreationContext(false);
    private boolean disposed;

    /** 新建部件，身份独立，结构定义由同端实例共享。 */
    public BodyPart(BodyPartType type, String variant, Level level) {
        super(type, variant, level, UUID.randomUUID(), type.getRegistryKey().toLanguageKey(), CONTEXT);
        initializeStructure();
    }

    /** 从保存数据恢复部件，完整结构检查先于刚体分配。 */
    public BodyPart(BodyPartType type, BodyPartData data, Level level, PartRestoreMode mode) {
        super(type, data.variant, level, UUID.fromString(data.uuid), data.name, CONTEXT);
        if (!variant.subParts.keySet().equals(data.subParts.keySet())) {
            throw new IllegalArgumentException("保存数据必须包含身体定义的完整子结构");
        }
        initializeStructure();
        try {
            // 使用公共完整／骨架恢复策略，避免维护第二套子结构恢复逻辑。
            restoreState(data, mode);
        } catch (RuntimeException | Error failure) {
            abortConstruction();
            throw failure;
        }
    }

    @Override public BodyPartData save() { return new BodyPartData(this); }
    @Override public boolean permitsSubsystemWork() { return !destroyed; }
    @Override public float durabilityLimitRatio() { return 1f; }
    @Override public float repairLimitRatio() { return 1f; }
    @Override public boolean permitsRepair() { return false; }
    @Override public float assemblyDamage(float amount, float remaining, boolean destroyed) { return amount; }

    /** 同步核心状态时保持部件身份和刚体实例，拒绝借快照更换定义。 */
    public void applySavedState(BodyPartData data) {
        if (!uuid.toString().equals(data.uuid) || !type.getRegistryKey().equals(data.registryKey)
                || !variantName.equals(data.variant) || !subParts.keySet().equals(data.subParts.keySet())) {
            throw new IllegalArgumentException("核心快照的身份、定义或结构不一致");
        }
        // 公共恢复器处理纹理、耐久与子结构；名称由部件自身维护。
        restoreState(data, PartRestoreMode.FULL_STATE);
        name = data.name;
    }

    /** 幂等释放子结构，同时清除未入世刚体的宿主引用。 */
    @Override public void destroy() {
        if (disposed) return;
        super.destroy();
        // 公共刚体销毁路径只在入世分支清理 owner，这里补齐占位身体的分支。
        subParts.values().forEach(child -> PhysicsBodyExtensionKt.setOwner(child.body, null));
        disposed = true;
        destroyed = true;
    }

    @Override public void addToLevel() {
        throw new UnsupportedOperationException("身体占位结构尚未接入物理装配");
    }
}
