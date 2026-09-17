package com.fruitfly.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * A zombie-bodied host driven by the fruit-fly connectome and learning controller.
 * It deliberately inherits FlyEntity so sensing, neural control, combat learning,
 * telemetry, and persistence remain identical while the registered mob is a monster.
 */
public final class BrainZombieEntity extends FlyEntity {
    public BrainZombieEntity(EntityType<? extends BrainZombieEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.ZOMBIE_AMBIENT; }

    @Override
    protected SoundEvent getHurtSound(net.minecraft.world.entity.damagesource.DamageSource source) {
        return SoundEvents.ZOMBIE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.ZOMBIE_DEATH; }

    @Override
    public float getVoicePitch() { return 0.9f + 0.08f * (float) Math.sin(tickCount * 0.1); }
}
