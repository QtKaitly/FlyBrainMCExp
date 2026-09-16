package com.fruitfly.entity;

import com.fruitfly.brain.SensoryFrame;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded tabular reinforcement learner for combat. The connectome remains the
 * primary controller; this layer learns only whether combat is worthwhile and
 * never bypasses Minecraft's normal damage/visibility rules.
 */
public final class LearningCombat {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final int ACTION_IGNORE = 0, ACTION_ATTACK = 1;
    private static final double ALPHA = 0.08, GAMMA = 0.9, EPSILON = 0.06;
    private static final int ATTACK_COOLDOWN = 12;
    private static final double ATTACK_RANGE_SQ = 2.25;

    private LearningCombat() {}

    private static final class State {
        final double[][] q = new double[8][2];
        int context, action;
        long lastHit = Long.MIN_VALUE;
        float damage;
        int idleTicks;
    }

    /** Called on the server thread once per game tick. */
    public static void tick(FlyEntity fly, SensoryFrame frame) {
        if (fly.level().isClientSide) return;
        State s = STATES.computeIfAbsent(fly.getUUID(), k -> new State());
        int next = context(fly, frame);
        double reward = -s.damage * 0.7;
        s.damage = 0;

        // TD(0): credit the previous decision with the consequence observed now.
        double old = s.q[s.context][s.action];
        s.q[s.context][s.action] = clamp(old + ALPHA *
                (reward + GAMMA * max(s.q[next]) - old));
        s.context = next;

        LivingEntity target = nearestTarget(fly);
        if (target == null) {
            s.action = ACTION_IGNORE;
            s.idleTicks++;
            return;
        }
        s.idleTicks = 0;

        // A small exploration rate prevents permanent early mistakes. The
        // learned policy is conservative until an attack has demonstrated value.
        double advantage = s.q[next][ACTION_ATTACK] - s.q[next][ACTION_IGNORE];
        boolean attack = fly.getRandom().nextDouble() < EPSILON
                ? fly.getRandom().nextBoolean() : advantage > -0.15;
        if (!attack) {
            s.action = ACTION_IGNORE;
            fly.setTarget(null);
            return;
        }

        s.action = ACTION_ATTACK;
        fly.setTarget(target);
        fly.getLookControl().setLookAt(target, 30f, 30f);
        if (fly.distanceToSqr(target) <= ATTACK_RANGE_SQ
                && fly.hasLineOfSight(target)
                && fly.tickCount - s.lastHit >= ATTACK_COOLDOWN) {
            if (fly.doHurtTarget(target)) {
                s.lastHit = fly.tickCount;
                // Immediate outcome: successful attacks reinforce this context.
                s.q[next][ACTION_ATTACK] = clamp(s.q[next][ACTION_ATTACK]
                        + ALPHA * (0.8 - s.q[next][ACTION_ATTACK]));
            }
        }
    }

    /** Converts damage received into delayed negative reinforcement. */
    public static void observeDamage(FlyEntity fly, float amount) {
        State s = STATES.get(fly.getUUID());
        if (s != null) s.damage = Math.min(2f, s.damage + Math.max(0f, amount) / 2f);
    }

    /** Persist the learned policy with the entity. */
    public static void save(FlyEntity fly, CompoundTag tag) {
        State s = STATES.get(fly.getUUID());
        if (s == null) return;
        tag.putInt("LearningContext", s.context);
        tag.putInt("LearningAction", s.action);
        tag.putLong("LearningLastHit", s.lastHit);
        for (int state = 0; state < 8; state++) {
            tag.putDouble("LearningQ" + state + "I", s.q[state][ACTION_IGNORE]);
            tag.putDouble("LearningQ" + state + "A", s.q[state][ACTION_ATTACK]);
        }
    }

    /** Restore a policy created by an older version only when all values are finite. */
    public static void load(FlyEntity fly, CompoundTag tag) {
        if (!tag.contains("LearningContext")) return;
        State s = STATES.computeIfAbsent(fly.getUUID(), k -> new State());
        s.context = Math.max(0, Math.min(7, tag.getInt("LearningContext")));
        s.action = tag.getInt("LearningAction") == ACTION_ATTACK ? ACTION_ATTACK : ACTION_IGNORE;
        s.lastHit = tag.getLong("LearningLastHit");
        for (int state = 0; state < 8; state++) {
            double ignore = tag.getDouble("LearningQ" + state + "I");
            double attack = tag.getDouble("LearningQ" + state + "A");
            s.q[state][ACTION_IGNORE] = finiteOrZero(ignore);
            s.q[state][ACTION_ATTACK] = finiteOrZero(attack);
        }
    }

    /** Release entity state when an entity is permanently removed. */
    public static void forget(FlyEntity fly) { STATES.remove(fly.getUUID()); }

    private static LivingEntity nearestTarget(FlyEntity fly) {
        AABB box = fly.getBoundingBox().inflate(8.0);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : fly.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != fly && e.isAlive() && !(e instanceof FlyEntity)
                        && !(e instanceof Player p && (p.isCreative() || p.isSpectator()))
                        && !e.isInvulnerable())) {
            double d = fly.distanceToSqr(e);
            if (d < bestD && fly.hasLineOfSight(e)) { bestD = d; best = e; }
        }
        return best;
    }

    private static int context(FlyEntity fly, SensoryFrame f) {
        int c = 0;
        if (f != null && f.damage > 0.05f) c |= 1;
        if (f != null && !Float.isNaN(f.odorBearingDeg)) c |= 2;
        if (fly.isFlyingState()) c |= 4;
        return c;
    }

    private static double max(double[] a) { return Math.max(a[0], a[1]); }
    private static double clamp(double v) { return Math.max(-4, Math.min(4, v)); }
    private static double finiteOrZero(double v) { return Double.isFinite(v) ? clamp(v) : 0; }
}
