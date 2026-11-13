package io.github.sweetzonzi.arms_core.common.control.attr;

import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;

/**
 * 一个驱动 Part 的地面能力：一个最大力 {@code F_i}、一个反拖上限 {@code B_i}、三个空载速度 {@code V_i,g}。
 *
 * <p>“一力三速”是这里的字段形态：三档共用同一个 {@code F_i} 与 {@code B_i}，只有零出力速度随档位变化。
 * 档位倍率不进入这里——{@code Gait#baseSpeedModifier} 只服务动画与既有的速度倍率消费者，
 * 不得乘进腿力或零载速度。
 *
 * <p>低速制动辅助 {@code brakeAssist} 在 0–1 之间：0 使用有限力制动，1 在低速区采用标准指数制动，
 * 中间值按比例混合。辅助范围由本 Part 的固定 {@code jogSpeed} 决定，不随当前档位变化。
 * 辅助只用于松键停车和有输入时收侧速。
 *
 * <p>校验在构造时一次完成：力与反拖不得为负，三个速度必须为正且有限，辅助值必须有限。速度为 0 会让
 * {@code 1 − (v/V)²} 失去定义，因此“这个 Part 当前不驱动”不表示为 {@code V = 0}，
 * 而是由调用方把它从有效 Part 列表里去掉（失去支撑、Part 失效等硬条件）。
 *
 * @param maxForce    正向力上限 {@code F_i}（N），零速可用的最大推进力
 * @param dragLimit   反拖力上限 {@code B_i}（N），超速时负向贡献的绝对值上限
 * @param walkSpeed   慢走档零出力速度 {@code V_i,CREEP}（m/s）
 * @param jogSpeed    常速档零出力速度 {@code V_i,JOG}（m/s）
 * @param sprintSpeed 冲刺档零出力速度 {@code V_i,SPRINT}（m/s）
 * @param brakeAssist 低速制动辅助混合比例 [0,1]，0 表示关闭；五参数构造器默认关闭
 * @author Sweetzonzi
 */
public record LegDriveAttr(float maxForce, float dragLimit,
                           float walkSpeed, float jogSpeed, float sprintSpeed, float brakeAssist) {

    public LegDriveAttr(float maxForce, float dragLimit,
                        float walkSpeed, float jogSpeed, float sprintSpeed) {
        this(maxForce, dragLimit, walkSpeed, jogSpeed, sprintSpeed, 0f);
    }

    public LegDriveAttr {
        requireNonNegative("maxForce", maxForce);
        requireNonNegative("dragLimit", dragLimit);
        requirePositiveSpeed("walkSpeed", walkSpeed);
        requirePositiveSpeed("jogSpeed", jogSpeed);
        requirePositiveSpeed("sprintSpeed", sprintSpeed);
        if (!Float.isFinite(brakeAssist) || brakeAssist < 0f || brakeAssist > 1f) {
            throw new IllegalArgumentException("brakeAssist 必须在 [0,1] 内且有限，实际为 " + brakeAssist);
        }
    }

    /**
     * 该档位的零出力速度 {@code V_i,g}。
     *
     * @param gait 驱动档位，只接受 {@link Gait#CREEP}、{@link Gait#JOG}、{@link Gait#SPRINT}
     * @return 零出力速度（m/s），恒为正
     * @throws IllegalArgumentException 传入的是非驱动档位
     */
    public double noLoadSpeed(Gait gait) {
        return switch (gait) {
            case CREEP -> walkSpeed;
            case JOG -> jogSpeed;
            case SPRINT -> sprintSpeed;
            default -> throw new IllegalArgumentException("不是驱动档位: " + gait);
        };
    }

    private static void requireNonNegative(String name, float value) {
        if (!Float.isFinite(value) || value < 0f) {
            throw new IllegalArgumentException(name + " 必须是有限的非负数，实际为 " + value);
        }
    }

    private static void requirePositiveSpeed(String name, float value) {
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IllegalArgumentException(name + " 必须是有限的正数，实际为 " + value);
        }
    }
}
