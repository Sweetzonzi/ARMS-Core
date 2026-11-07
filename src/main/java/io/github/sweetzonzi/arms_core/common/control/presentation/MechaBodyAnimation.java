package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import cn.solarmoon.spark_core.animation.model.origin.OBone;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import cn.solarmoon.spark_core.util.SparkMathKt;
import com.jme3.bullet.RotationOrder;
import com.jme3.bullet.joints.JointEnd;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.bullet.joints.motors.RotationMotor;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaModelPreset;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.part.ArmsBodyAssembly;
import io.github.sweetzonzi.arms_core.common.part.BodyPart;
import lombok.Getter;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** AllBody 的整机动画体。两端共享 bmpt 姿态规则，服务端将肢体骨骼的相对旋转写入关节。 */
public final class MechaBodyAnimation implements IAnimatable<ArmsCore> {
    private static final float TICKS_PER_SECOND = 20f;
    private static final float YAW_SPEED_SMOOTHING = 0.4f;
    private static final float HIP_TORQUE_NM = 600f;
    private static final float KNEE_TORQUE_NM = 180f;
    private static final float SERVO_SPEED_RAD = (float) Math.toRadians(360);

    @Getter
    private final ArmsCore animatable;
    @Getter
    private final ModelController modelController;
    @Getter
    private final AnimController animController;
    @Getter
    private final MechaMolangContext molangContext;
    @Getter
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final List<Drive> drives = new ArrayList<>();
    private @Nullable ModelPose boundPose;
    private volatile Vec3 position = Vec3.ZERO;
    private final Vector3f presentationPosition = new Vector3f();
    private float lifeTime;
    private float previousYaw = Float.NaN;
    private float clientYawSpeed;

    public MechaBodyAnimation(ArmsCore core) {
        animatable = core;
        modelController = new ModelController(this);
        animController = new AnimController(this);
        molangContext = new MechaMolangContext(this);
    }

    @Override public Level getAnimLevel() { return animatable.getLevel(); }
    @Override public ModelIndex getDefaultModelIndex() { return MechaModelPreset.FRAME_MODEL; }
    @Override public Vec3 getRenderPosition(Number partialTicks) { return position; }
    @Override public Matrix4f getWorldPositionMatrix(Number partialTicks) {
        return new Matrix4f().translation((float) position.x, (float) position.y, (float) position.z);
    }

    public void ensureModel() {
        var model = modelController.getModel();
        if ((model == null || model.getPose().getBonePoseList().isEmpty())
                && OModel.getORIGINS().containsKey(getDefaultModelIndex())) {
            modelController.setModel(getDefaultModelIndex());
        }
    }

    /** 服务端物理线程采样控制器的运动与输入。 */
    public void updateServerInputs(MechaControl control, float dt) {
        var body = control.getBody();
        var velocity = body.getLinearVelocity(null);
        var location = body.getPhysicsLocation(null);
        position = new Vec3(location.x, location.y, location.z);
        var input = control.getConditionSnapshot();
        float yaw = input.viewYaw();
        float yawRate = Float.isNaN(previousYaw) ? 0 : Mth.wrapDegrees(yaw - previousYaw) / dt;
        previousYaw = yaw;
        molangContext.setFacing(yaw, yawRate);
        molangContext.setHeadRotation(input.viewPitch(), 0);
        publishMotion(velocity.x, velocity.y, velocity.z, dt);
    }

    /** 客户端主线程发布同步运动量和用于 LOD 的呈现位置。 */
    public void updateClientInputs() {
        var data = animatable.getSyncedData();
        if (!animatable.lerpPresentationPosition(1f, presentationPosition)) {
            presentationPosition.set(data.get(ArmsCore.DATA_POS));
        }
        position = new Vec3(presentationPosition.x, presentationPosition.y, presentationPosition.z);

        float yaw = data.get(ArmsCore.DATA_YAW).getY();
        if (!Float.isNaN(previousYaw)) {
            float yawRate = Mth.wrapDegrees(yaw - previousYaw) * TICKS_PER_SECOND;
            clientYawSpeed += (yawRate - clientYawSpeed) * YAW_SPEED_SMOOTHING;
        }
        previousYaw = yaw;
        molangContext.setFacing(yaw, clientYawSpeed);
        var velocity = data.get(ArmsCore.DATA_VEL);
        publishMotion(velocity.x, velocity.y, velocity.z, 1f / TICKS_PER_SECOND);
    }

    private void publishMotion(float x, float y, float z, float dt) {
        lifeTime += dt;
        molangContext.setMotion((float) Math.hypot(x, z), y);
        molangContext.setPositionDelta(x / TICKS_PER_SECOND, y / TICKS_PER_SECOND, z / TICKS_PER_SECOND);
        molangContext.setLifeTime(lifeTime);
    }

    public void drive(MechaControl control) {
        var model = modelController.getModel();
        if (!control.getPresentation().isStarted() || model == null || !animController.isPlayingAnim()
                || control.getCurrentPosture() == Posture.RAGDOLL || control.getConditionSnapshot().isDead()) {
            releaseDrives();
            return;
        }
        var pose = model.getPose();
        if (pose != boundPose) {
            releaseDrives();
            bindDrives(pose);
            boundPose = pose;
        }
        Function<String, Vec3> rotationAt = name -> {
            var transform = pose.getPhysicsLocalTransform(name);
            return transform == null ? Vec3.ZERO : transform.getRotation();
        };
        for (var drive : drives) {
            if (!drive.isLive()) continue;
            var current = relativeRotation(drive.chain, rotationAt);
            Quaternionf delta = new Quaternionf(drive.jointToBone).conjugate()
                    .mul(drive.inverseRest).mul(current).mul(drive.jointToBone);
            drive.command(xRotation(delta));
        }
    }

    private void bindDrives(ModelPose pose) {
        var rig = pose.getModel().getOrigin();
        for (var link : animatable.getBodyLinks()) {
            var parent = link.advanced().subPart;
            var child = link.simple().subPart;
            New6Dof joint = link.advanced().joint;
            if (!(parent.part instanceof BodyPart) || !(child.part instanceof BodyPart) || joint == null
                    || joint.getPhysicsSpace() == null || joint.getRotationOrder() != RotationOrder.XYZ
                    || !joint.getBodyA().isDynamic() || !joint.getBodyB().isDynamic()) continue;

            String parentBone = parent.attr.getStartBone();
            String childBone = child.attr.getStartBone();
            List<OBone> chain = chainBetween(rig, parentBone, childBone);
            OBone childBind = child.getBones().get(childBone);
            if (chain.isEmpty() || childBind == null) {
                ARMS.LOGGER.warn("共享动画找不到关节骨骼链 {} → {}", parentBone, childBone);
                continue;
            }

            // 从关节 B 端换到子骨骼静止轴。质心与关节参考系均取实际装配结果。
            var bind = childBind.getRotation();
            Quaternionf jointToBone = new Quaternionf().rotationZYX((float) bind.z, (float) bind.y, (float) bind.x)
                    .conjugate().mul(SparkMathKt.toQuaternionf(child.getLocalMassCenterTransform().getRotation()))
                    .mul(SparkMathKt.toQuaternionf(joint.getFrameTransform(JointEnd.B, null).getRotation()));
            float torque = parent == animatable.getRootSubPart() ? HIP_TORQUE_NM : KNEE_TORQUE_NM;
            drives.add(new Drive(link, joint, torque, chain,
                    relativeRotation(chain, name -> Vec3.ZERO).conjugate(), jointToBone));
        }
    }

    /** 骨骼路径排除父部件的基准骨骼，整机共同转动不会进入关节目标。 */
    static List<OBone> chainBetween(OModel rig, String parent, String child) {
        var chain = new ArrayList<OBone>();
        OBone bone = rig.getBone(child);
        while (bone != null && !bone.getName().equals(parent)) {
            chain.add(bone);
            bone = bone.getParent();
        }
        if (bone == null) return List.of();
        Collections.reverse(chain);
        return chain;
    }

    static Quaternionf relativeRotation(List<OBone> chain, Function<String, Vec3> animationRotation) {
        Quaternionf rotation = new Quaternionf();
        for (OBone bone : chain) {
            Vec3 angles = bone.getRotation().add(animationRotation.apply(bone.getName()));
            rotation.rotateZYX((float) angles.z, (float) angles.y, (float) angles.x);
        }
        return rotation;
    }

    static float xRotation(Quaternionf q) {
        return (float) Math.atan2(2f * (q.w * q.x + q.y * q.z), 1f - 2f * (q.x * q.x + q.y * q.y));
    }

    public void releaseDrives() {
        for (var drive : drives) {
            if (drive.isLive()) drive.restore();
        }
        drives.clear();
        boundPose = null;
    }

    private static final class Drive {
        private final ArmsBodyAssembly.Link link;
        private final New6Dof joint;
        private final List<OBone> chain;
        private final Quaternionf inverseRest;
        private final Quaternionf jointToBone;
        private final RotationMotor motor;
        private final float torque;
        private final boolean oldMotorEnabled;
        private final boolean oldServoEnabled;
        private final boolean oldSpringEnabled;
        private final float oldTorque;
        private final float oldSpeed;
        private final float oldTarget;

        private Drive(ArmsBodyAssembly.Link link, New6Dof joint, float torque,
                      List<OBone> chain, Quaternionf inverseRest, Quaternionf jointToBone) {
            this.link = link;
            this.joint = joint;
            this.chain = chain;
            this.inverseRest = inverseRest;
            this.jointToBone = jointToBone;
            this.torque = torque;
            motor = joint.getRotationMotor(0);
            oldMotorEnabled = motor.isMotorEnabled();
            oldServoEnabled = motor.isServoEnabled();
            oldSpringEnabled = motor.isSpringEnabled();
            oldTorque = motor.get(MotorParam.MaxMotorForce);
            oldSpeed = motor.get(MotorParam.TargetVelocity);
            oldTarget = motor.get(MotorParam.ServoTarget);
        }

        boolean isLive() {
            return link.advanced().joint == joint && link.simple().joint == joint && joint.getPhysicsSpace() != null;
        }

        void command(float radians) {
            if (!Float.isFinite(radians)) return;
            // 工程角是 A 到 B 的局部 X 旋转，Bullet 的伺服目标使用相反符号。
            float lower = motor.get(MotorParam.LowerLimit);
            float upper = motor.get(MotorParam.UpperLimit);
            float target = lower <= upper ? Math.clamp(-radians, lower, upper) : -radians;
            joint.enableSpring(3, false);
            motor.set(MotorParam.MaxMotorForce, torque);
            motor.set(MotorParam.TargetVelocity, SERVO_SPEED_RAD);
            motor.set(MotorParam.ServoTarget, target);
            motor.setMotorEnabled(true);
            motor.setServoEnabled(true);
            joint.getBodyA().activate();
            joint.getBodyB().activate();
        }

        void restore() {
            motor.setMotorEnabled(oldMotorEnabled);
            motor.setServoEnabled(oldServoEnabled);
            joint.enableSpring(3, oldSpringEnabled);
            motor.set(MotorParam.MaxMotorForce, oldTorque);
            motor.set(MotorParam.TargetVelocity, oldSpeed);
            motor.set(MotorParam.ServoTarget, oldTarget);
        }
    }
}
