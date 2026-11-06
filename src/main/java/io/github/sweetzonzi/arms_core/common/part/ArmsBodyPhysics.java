package io.github.sweetzonzi.arms_core.common.part;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.RotationOrder;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPart;
import jme3utilities.math.MyMath;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 服务端身体的物理入世批次。运动学锚点只读取控制器的位姿。 */
public final class ArmsBodyPhysics {
    /** 锚点偏航的误差增益（每秒）和补偿速度上限（弧度每秒）。 */
    private static final float ROOT_YAW_GAIN = 30f;
    private static final float ROOT_MAX_YAW_SPEED = 40f;
    /** 平移误差增益（每秒）和补偿速度上限（米每秒）。 */
    private static final float ROOT_POSITION_GAIN = 25f;
    private static final float ROOT_MAX_POSITION_SPEED = 6f;
    /** 身体模型的正面是局部 -Z，控制器的前轴是局部 +Z。 */
    private static final Quaternion BODY_FACING = new Quaternion(0f, 1f, 0f, 0f);
    private final List<PhysicsRigidBody> bodies;
    private final List<New6Dof> joints;
    /** 给核心约束提供可逐步移动和转向的运动学端点，约束冲量由该端点承担。 */
    private final PhysicsRigidBody anchor;
    private final New6Dof rootJoint;
    private final float coreHeightOffset;
    private final Transform targetPose = new Transform();
    private final Vector3f targetVelocity = new Vector3f();
    private final Vector3f positionCorrection = new Vector3f();
    private final Matrix3f facing = new Matrix3f();
    /**
     * 本步写进锚点的线速度 / 角速度副本。
     * <p>
     * 运动学刚体的 {@code getLinearVelocity} 在 Minie 里带 {@code assert isDynamic()}，读不回来；
     * 这两个字段保存的是「求解器这一步看到的目标速度」，调试录制用它代替读取。
     */
    private final Vector3f anchorLinearVelocity = new Vector3f();
    private final Vector3f anchorAngularVelocity = new Vector3f();

    public ArmsBodyPhysics(BodyPart core, Collection<AbstractPart<?>> parts, Transform controllerPose, float coreHeightOffset) {
        this.coreHeightOffset = coreHeightOffset;
        var children = parts.stream().flatMap(part -> part.subParts.values().stream()).toList();
        bodies = children.stream().map(child -> child.body).toList();
        joints = children.stream().flatMap(child -> child.connectors.values().stream())
                .map(connector -> connector.joint).filter(Objects::nonNull).distinct().toList();

        // 以核心根对齐出生位姿，保留身体各关节当前的相对姿态。
        var target = bodyTarget(controllerPose.clone());
        var fromRoot = core.rootSubPart.body.getTransform(null).invert();
        for (var part : parts) {
            var relative = MyMath.combine(part.rootSubPart.body.getTransform(null), fromRoot, new Transform());
            part.setTransform(MyMath.combine(relative, target, new Transform()));
            part.applyGravity();
        }
        anchor = new PhysicsRigidBody(new BoxCollisionShape(0.01f), 0f);
        anchor.setKinematic(true);
        anchor.setCollisionGroup(CollisionGroups.TRIGGER);
        anchor.setCollideWithGroups(CollisionGroups.NONE);
        anchor.setContactResponse(false);
        anchor.setPhysicsTransform(target);

        // 锁住六个自由度。Y 不作中间轴，避免快速偏航超过 90° 时被分解成俯仰和侧滚。
        rootJoint = new New6Dof(anchor, core.rootSubPart.body, Vector3f.ZERO, Vector3f.ZERO,
                new Matrix3f(), new Matrix3f(), RotationOrder.YXZ);
        for (int axis = 0; axis < 6; axis++) {
            rootJoint.set(MotorParam.LowerLimit, axis, 0f);
            rootJoint.set(MotorParam.UpperLimit, axis, 0f);
        }
    }

    /** 在一个物理线程任务中先放刚体，再添加去重后的内部与外部关节。 */
    public void enter(PhysicsSpace space, PhysicsRigidBody controller) {
        space.addCollisionObject(anchor);
        for (var body : bodies) {
            // MM 子刚体能碰撞 PAWN；同一机体必须显式跳过这对接触。
            body.addToIgnoreList(controller);
            space.addCollisionObject(body);
        }
        joints.forEach(space::addJoint);
        space.addJoint(rootJoint);
    }

    /** 运动学锚点刚体；调试录制需要读它的真实位姿。 */
    public PhysicsRigidBody anchor() {
        return anchor;
    }

    /** 本步写进锚点的线速度副本（运动学刚体的速度读不回来，见字段注释）。 */
    public Vector3f anchorLinearVelocity(Vector3f store) {
        return (store == null ? new Vector3f() : store).set(anchorLinearVelocity);
    }

    /** 本步写进锚点的角速度副本。 */
    public Vector3f anchorAngularVelocity(Vector3f store) {
        return (store == null ? new Vector3f() : store).set(anchorAngularVelocity);
    }

    /** 在控制器消费本步输入之后、Bullet 求解之前更新约束目标。 */
    public void followControllerStep(PhysicsRigidBody controller) {
        anchor.setPhysicsTransform(bodyTarget(controller.getTransform(targetPose)));
        var root = rootJoint.getBodyB();
        // 越障等直接位移不会体现在控制器速度里；用限速补偿追上位置误差。
        root.getPhysicsLocation(positionCorrection);
        targetPose.getTranslation().subtract(positionCorrection, positionCorrection);
        positionCorrection.multLocal(ROOT_POSITION_GAIN);
        float correctionSpeed = positionCorrection.length();
        if (correctionSpeed > ROOT_MAX_POSITION_SPEED) {
            positionCorrection.multLocal(ROOT_MAX_POSITION_SPEED / correctionSpeed);
        }
        anchor.setLinearVelocity(controller.getLinearVelocity(targetVelocity).addLocal(positionCorrection));
        anchorLinearVelocity.set(targetVelocity);

        targetPose.getRotation().toRotationMatrix(facing);
        float targetX = facing.get(0, 2);
        float targetZ = facing.get(2, 2);
        root.getPhysicsRotationMatrix(facing);
        float rootX = facing.get(0, 2);
        float rootZ = facing.get(2, 2);
        // 控制器直接改写 yaw，求解器速度没有包含这次转向。给锚点补上限速的最短偏航修正。
        float yawError = (float) Math.atan2(rootZ * targetX - rootX * targetZ, rootX * targetX + rootZ * targetZ);
        controller.getAngularVelocity(targetVelocity);
        targetVelocity.y += Math.clamp(yawError * ROOT_YAW_GAIN, -ROOT_MAX_YAW_SPEED, ROOT_MAX_YAW_SPEED);
        anchor.setAngularVelocity(targetVelocity);
        anchorAngularVelocity.set(targetVelocity);
        root.activate();
    }

    /** 对齐模型前轴，并沿世界竖直方向应用素体的站姿高差。 */
    private Transform bodyTarget(Transform pose) {
        pose.getRotation().multLocal(BODY_FACING);
        // TODO：换腿后由腿部零件提供站立和蹲伏等姿态的支撑高度（脚底至髋挂点）。
        // 加上髋挂点至核心质心的固定高差（与默认腿长无关），再减去当前控制器中心离脚底的高度。
        pose.getTranslation().y += coreHeightOffset;
        return pose;
    }

    /** 传送时整体平移，避免约束跨远距离拉拽肢体。 */
    public void warp(Vector3f delta, Vector3f controllerVelocity) {
        for (var body : bodies) {
            body.setPhysicsLocation(body.getPhysicsLocation(null).addLocal(delta));
            body.activate();
        }
        // 约束两端同量平移；下一次普通子步负责跟随补偿。
        anchor.setPhysicsLocation(anchor.getPhysicsLocation(null).addLocal(delta));
        anchor.setLinearVelocity(controllerVelocity);
    }

    /** 在物理线程移除已加入的对象，也可回滚只完成一部分的入世。 */
    public void leave(PhysicsSpace space) {
        if (rootJoint.getPhysicsSpace() == space) space.removeJoint(rootJoint);
        for (var joint : joints) {
            if (joint.getPhysicsSpace() == space) space.removeJoint(joint);
        }
        for (var body : bodies) {
            if (body.isInWorld()) space.removeCollisionObject(body);
        }
        if (anchor.isInWorld()) space.removeCollisionObject(anchor);
    }

    /** 移除后解除刚体上的本地引用；维度关闭时物理空间已负责移除对象。 */
    public void release(PhysicsRigidBody controller) {
        bodies.forEach(body -> body.removeFromIgnoreList(controller));
        joints.forEach(New6Dof::destroy);
        rootJoint.destroy();
    }
}
