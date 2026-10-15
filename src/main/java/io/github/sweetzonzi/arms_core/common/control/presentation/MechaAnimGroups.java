package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.anim.AnimGroups;

/**
 * 本项目私有的动画层号语义别名。
 * <p>
 * 四台姿态状态机共用一层，取 Spark-Core 的 {@link AnimGroups#LOCOMOTION}。常驻并行动画不在这里：
 * 它们的层号由 {@link AnimGroups#persistent(int)} 直接给出（槽位 N 对应层号 −8+N，
 * 见 {@code docs/表现层动画接入实现计划.md} D5），本类不再包一层。
 * <p>
 * 本类只提供别名而不重新定义数值：层号是 Spark-Core 的契约
 * （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/animation/anim/AnimGroups.kt#AnimGroups}），
 * 在项目侧再写一遍数字只会让两处漂移。
 *
 * @author Sweetzonzi
 */
public final class MechaAnimGroups {

    /** 四台姿态状态机的写入层 —— Spark-Core 的 LOCOMOTION(2)。 */
    public static final int LOCOMOTION = AnimGroups.LOCOMOTION;

    private MechaAnimGroups() {
    }
}
