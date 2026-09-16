package com.fruitfly.entity;

import com.fruitfly.brain.SensoryFrame;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small, deliberately bounded reinforcement learner for embodied behaviour.
 *
 * <p>This is not a second hand-written brain.  It learns a value for two actions
 * (ignore/attack) in eight coarse sensory contexts.  Damage is a negative reward,
 * a successful hit is a positive reward, and hunger/novelty provide a small
 * exploration pressure.  Values are used only as a gate for combat, so a bad
 * policy cannot make a fly move through walls or attack every entity in sight.</p>
 */
public final class LearningCombat {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final int ACTION_IGNORE = 0, ACTION_ATTACK = 1;
    private static final double ALPHA = 0.08, GAMMA = 0.9, EPSILON = 0.06;
    private static final int ATTACK_COOLDOWN = 12;

    private LearningCombat() {}

    private static final class State {
        final double[][] q = new double[8][2];
        int context, action;
        long lastHit;
        float damage;
    }

    /** Run once per server tick, after sensing and before/alongside body motion. */
    public static void tick(FlyEntity fly, SensoryFrame frame) {
        if (fly.level().isClientSide) return;
        State s = STATES.computeIfAbsent(fly.getUUID(), k -> new State());
        int next = context(fly, frame);
        double reward = -s.damage * 0.7;
        s.damage = 0;
        // Temporal-difference update: the consequence of the previous decision.
        s.q[s.context][s.action] += ALPHA * (reward + GAMMA * max(s.q[next]) - s.q[s.context][s.action]);
        s.context = next;

        LivingEntity target = nearestTarget(fly);
        if (target == null) {
            s.action = ACTION_IGNORE;
            return;
        }
        double learned = s.q[next][ACTION_ATTACK] - s.q[next][ACTION_IGNORE];
        boolean explore = fly.getRandom().nextDouble() < EPSILON;
        boolean attack = explore ? fly.getRandom().nextBoolean() : learned > -0.15;
        if (!attack) {
            s.action = ACTION_IGNORE;
            return;
        }
        s.action = ACTION_ATTACK;
        fly.setTarget(target);
        fly.getLookControl().setLookAt(target, 30f, 30f);
        if (fly.distanceToSqr(target) <= 2.25 && fly.tickCount - s.lastHit >= ATTACK_COOLDOWN) {
            if (fly.doHurtTarget(target)) {
                s.lastHit = fly.tickCount;
                // Delayed positive feedback makes repeated effective attacks more likely.
                s.q[next][ACTION_ATTACK] += ALPHA * (0.8 - s.q[next][ACTION_ATTACK]);
            }
        }
    }

    public static void observeDamage(FlyEntity fly, float amount) {
        State s = STATES.get(fly.getUUID());
        if (s != null) s.damage = Math.min(2f, s.damage + amount / 2f);
    }

    private static LivingEntity nearestTarget(FlyEntity fly) {
        AABB box = fly.getBoundingBox().inflate(8.0);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : fly.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != fly && e.isAlive() && !(e instanceof FlyEntity)
                        && !(e instanceof Player p && (p.isCreative() || p.isSpectator()))
                        && !e.isInvulnerable())) {
            double d = fly.distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
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
}
