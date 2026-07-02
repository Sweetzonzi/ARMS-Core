package io.github.sweetzonzi.arms_core.common;

import io.github.sweetzonzi.machine_max.common.mech.subsystem.SubsystemController;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.IPartAssembly;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.Part;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.connector.AbstractConnector;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 机娘核心类
 */
public class ArmsCore implements IPartAssembly {
    @Override
    public UUID getAssemblyId() {
        return null;
    }

    @Override
    public Level getLevel() {
        return null;
    }

    @Override
    public boolean isInLevel() {
        return false;
    }

    @Override
    public String getAssemblyName() {
        return "";
    }

    @Override
    public void setAssemblyName(String name) {

    }

    @Override
    public float getTotalMass() {
        return 0;
    }

    @Override
    public void addPart(Part part) {

    }

    @Override
    public void removePart(Part part) {

    }

    @Override
    public void connect(AbstractConnector connector1, AbstractConnector connector2, @Nullable Part newPart) {

    }

    @Override
    public void disconnect(AbstractConnector connector) {

    }

    @Override
    public SubsystemController getSubsystemController() {
        return null;
    }

    @Override
    public void onPartDamage(Part part, float damage) {

    }

    @Override
    public void activatePhysics() {

    }
}
