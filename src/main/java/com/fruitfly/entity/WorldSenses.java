package com.fruitfly.entity;

import com.fruitfly.FruitFlyConfig;
import com.fruitfly.brain.MotorDecoder;
import com.fruitfly.brain.RetinaGeometry;
import com.fruitfly.brain.SensoryFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Samples the Minecraft world into a {@link SensoryFrame} in the fly's head frame each server tick.
 *
 * <p>Head frame: x forward, y up, z right (fly's right). Minecraft yaw 0 faces +Z; forward = (−sin yaw, 0, cos yaw),
 * right = (−cos yaw, 0, −sin yaw).</p>
 *
 * Budget per fly per tick: half of the coarse retina rays (≈130 clips of 24 blocks), one entity scan, one item
 * scan; the block odor scan (radius 5) runs every 4 ticks.
 */
public final class WorldSenses {
    public static final class State {
        int rayPhase;
        float prevYaw = Float.NaN;
        final Map<Integer, float[]> prevObjects = new HashMap<>(); // entityId -> {size, azimuth, elevation, lastTick}
        final Map<String, Float> blockOdor = new HashMap<>();
        double blockOdorBearingX, blockOdorBearingZ, blockOdorTotal;
        int blockScanTick = -1000;
        float[] rayLum;
        public float touchTimer;
    }

    private WorldSenses() {}

    public static Vec3 forward(float yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(y), 0, Math.cos(y));
    }

    public static Vec3 right(float yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new Vec3(-Math.cos(y), 0, -Math.sin(y));
    }

    /** World direction → head-frame (azimuth, elevation) in degrees. */
    public static float[] toHead(Vec3 dir, float yawDeg) {
        Vec3 f = forward(yawDeg), r = right(yawDeg);
        double len = dir.length();
        if (len < 1e-9) return new float[]{0, 0};
        double xf = dir.dot(f) / len, zr = dir.dot(r) / len, yu = dir.y / len;
        return new float[]{(float) Math.toDegrees(Math.atan2(zr, xf)), (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, yu))))};
    }

    /** Head-frame unit direction → world direction. */
    public static Vec3 toWorld(float dx, float dy, float dz, float yawDeg) {
        Vec3 f = forward(yawDeg), r = right(yawDeg);
        return new Vec3(f.x * dx + r.x * dz, dy, f.z * dx + r.z * dz);
    }

    public static void sample(FlyEntity fly, SensoryFrame frame, RetinaGeometry geom, FruitFlyConfig cfg, State st) {
        Level level = fly.level();
        frame.clear();
        float yaw = fly.getYRot();
        Vec3 eye = fly.getEyePosition();
        double dt = 0.05;

        // --- self-motion ---
        if (!Float.isNaN(st.prevYaw)) {
            float d = yaw - st.prevYaw;
            while (d > 180) d -= 360;
            while (d < -180) d += 360;
            frame.yawRateDegPerS = (float) (d / dt);
        }
        st.prevYaw = yaw;
        Vec3 v = fly.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double airspeed = Math.min(1.0, speed / Math.max(1e-3, cfg.flightSpeedBlocksPerS / 20.0));
        double side = speed > 1e-4 ? v.dot(right(yaw)) / speed : 0; // + = drifting right
        frame.windLeft = (float) (airspeed * (1 - 0.4 * side));
        frame.windRight = (float) (airspeed * (1 + 0.4 * side));
        frame.airborne = !fly.onGround();
        frame.legsOnGround = fly.onGround();
        frame.wingbeat = fly.isFlyingState() ? 1f : 0f;
        frame.tilt = 0;

        // --- mechanosensation / nociception ---
        float dmg = fly.consumeDamage();
        frame.damage = dmg;
        if (fly.horizontalCollision) st.touchTimer = 2;
        if (st.touchTimer > 0) {
            st.touchTimer--;
            frame.touchHead = 0.8f;
            frame.touchLegs = 0.5f;
        }
        BlockPos pos = fly.blockPosition();
        boolean raining = level.isRainingAt(pos);
        if (raining) {
            frame.groomDust = 0.6f;
            frame.moist = 0.8f;
        }
        if (fly.isInWater()) {
            frame.moist = 1f;
            frame.touchLegs = Math.max(frame.touchLegs, 0.5f);
        }
        float temp = level.getBiome(pos).value().getBaseTemperature();
        if (temp > 1.2f) frame.hot = Math.max(frame.hot, Math.min(1f, (temp - 1.0f)));
        if (temp < 0.15f) frame.cold = Math.max(frame.cold, Math.min(1f, (0.3f - temp) / 0.3f));
        if (temp > 1.5f && !raining) frame.dry = 0.6f;

        // --- olfaction: items ---
        double odorR = cfy(cfg.odorRadius);
        double lambda = cfg.odorFalloffBlocks;
        double bx = 0, bz = 0, total = 0;
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, fly.getBoundingBox().inflate(odorR), e -> true);
        for (ItemEntity it : items) {
            OdorTable.Odor o = OdorTable.forItem(it.getItem());
            if (o == null) continue;
            Vec3 rel = it.position().subtract(eye);
            double d = rel.length();
            double conc = o.strength() * Math.exp(-d / lambda) * (0.6 + 0.4 * Math.min(1, it.getItem().getCount() / 8.0));
            for (Map.Entry<String, Float> g : o.glomeruli().entrySet()) frame.addOdor(g.getKey(), (float) (g.getValue() * conc));
            if (d > 1e-6) {
                bx += rel.x / d * conc;
                bz += rel.z / d * conc;
                total += conc;
            }
        }
        // --- olfaction: blocks (cached scan) ---
        if (fly.tickCount - st.blockScanTick >= 4) {
            st.blockScanTick = fly.tickCount;
            st.blockOdor.clear();
            st.blockOdorBearingX = st.blockOdorBearingZ = st.blockOdorTotal = 0;
            int r = 5;
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -3; dy <= 3; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        mp.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        BlockState bs = level.getBlockState(mp);
                        if (bs.isAir()) continue;
                        OdorTable.Odor o = OdorTable.forBlock(bs);
                        if (o == null) continue;
                        double d = Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.5;
                        double conc = o.strength() * Math.exp(-d / lambda) * 0.5;
                        for (Map.Entry<String, Float> g : o.glomeruli().entrySet()) st.blockOdor.merge(g.getKey(), (float) (g.getValue() * conc), Float::sum);
                        st.blockOdorBearingX += dx / d * conc;
                        st.blockOdorBearingZ += dz / d * conc;
                        st.blockOdorTotal += conc;
                    }
        }
        for (Map.Entry<String, Float> e : st.blockOdor.entrySet()) frame.addOdor(e.getKey(), e.getValue());
        // block-grid offsets from blockPosition() and item vectors from the eye are both world-frame, so they sum;
        // the block concentration must count toward `total` or block-only sources never yield a bearing
        bx += st.blockOdorBearingX;
        bz += st.blockOdorBearingZ;
        total += st.blockOdorTotal;
        // --- olfaction: other flies (cVA from males) and CO2 from players ---
        List<Entity> nearby = level.getEntitiesOfClass(Entity.class, fly.getBoundingBox().inflate(cfy(cfg.visionRayLength)), e -> e != fly && !(e instanceof ItemEntity));
        for (Entity e : nearby) {
            Vec3 rel = e.getBoundingBox().getCenter().subtract(eye);
            double d = rel.length();
            if (e instanceof FlyEntity other) {
                double conc = Math.exp(-d / 3.0);
                if (other.isMale()) {
                    frame.addOdor("DA1", (float) conc);
                    frame.addOdor("DL3", (float) (0.6 * conc));
                } else {
                    frame.addOdor("VA1v", (float) (0.9 * conc));
                    frame.addOdor("VA1d", (float) (0.8 * conc));
                }
                if (other.getMode() == MotorDecoder.Mode.SONG && d < 3) frame.song = Math.max(frame.song, (float) (1 - d / 3));
            } else if (e instanceof net.minecraft.world.entity.player.Player) {
                double conc = 0.4 * Math.exp(-d / 4.0);
                frame.addOdor("V", (float) conc);
                frame.addOdor("VM1", (float) (0.5 * conc));
            }
        }
        if (total > 1e-6) {
            Vec3 bearing = new Vec3(bx, 0, bz);
            frame.odorBearingDeg = toHead(bearing, yaw)[0];
        }
        // clamp odor drive
        for (Map.Entry<String, Float> e : frame.odor.entrySet()) e.setValue(Math.min(1.5f, e.getValue()));

        // --- gustation: contact ---
        MotorDecoder.MotorCommand cmd = fly.latestCommand();
        boolean proboscisOut = cmd != null && cmd.feed > 0.2;
        AABB mouth = fly.getBoundingBox().inflate(0.35);
        for (ItemEntity it : items) {
            if (!it.getBoundingBox().intersects(mouth)) continue;
            TasteTable.Taste t = TasteTable.forItem(it.getItem());
            if (t == null) continue;
            applyTaste(frame, t, proboscisOut);
            if (proboscisOut) fly.feed(t.nutrition() * 0.02f);
        }
        BlockState below = level.getBlockState(pos.below());
        TasteTable.Taste tb = TasteTable.forBlock(below);
        if (tb == null) tb = TasteTable.forBlock(level.getBlockState(pos));
        if (tb != null) {
            applyTaste(frame, tb, proboscisOut);
            if (proboscisOut) fly.feed(tb.nutrition() * 0.02f);
        }
        if (fly.isInWater() || (raining && fly.onGround())) applyTaste(frame, TasteTable.WATER, proboscisOut || fly.isInWater());
        // pheromone contact with other flies (tapping)
        for (Entity e : nearby) {
            if (e instanceof FlyEntity other && other.getBoundingBox().intersects(mouth)) {
                applyTaste(frame, other.isMale() ? TasteTable.MALE_PHEROMONE : TasteTable.FEMALE_PHEROMONE, false);
            }
        }

        // --- vision: objects ---
        if (cfg.objectVision) {
            int now = fly.tickCount;
            for (Entity e : nearby) {
                Vec3 rel = e.getBoundingBox().getCenter().subtract(eye);
                double d = rel.length();
                if (d < 0.05) continue;
                AABB bb = e.getBoundingBox();
                double radius = 0.5 * Math.max(bb.getXsize(), Math.max(bb.getYsize(), bb.getZsize()));
                float size = (float) Math.toDegrees(2 * Math.atan(radius / d));
                float[] ae = toHead(rel, yaw);
                float[] prev = st.prevObjects.get(e.getId());
                float expansion = 0, angSpeed = 0;
                if (prev != null && now - prev[3] <= 2) {
                    float ticks = now - prev[3];
                    expansion = (size - prev[0]) / (float) (ticks * dt);
                    float daz = ae[0] - prev[1];
                    while (daz > 180) daz -= 360;
                    while (daz < -180) daz += 360;
                    float del = ae[1] - prev[2];
                    angSpeed = (float) (Math.sqrt(daz * daz + del * del) / (ticks * dt));
                }
                st.prevObjects.put(e.getId(), new float[]{size, ae[0], ae[1], now});
                SensoryFrame.VisualObject o = new SensoryFrame.VisualObject(ae[0], ae[1], size, expansion, angSpeed, e instanceof FlyEntity);
                o.contrast = e instanceof FlyEntity ? 0.9f : 0.8f;
                frame.objects.add(o);
            }
            Iterator<Map.Entry<Integer, float[]>> it = st.prevObjects.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue()[3] > 20) it.remove();
        }

        // --- vision: retina rays (half per tick) ---
        if (cfg.vision && geom != null) {
            int n = geom.columnCount();
            if (frame.luminance.length != n) frame.luminance = new float[n];
            java.util.Arrays.fill(frame.luminance, Float.NaN);
            int totalRays = geom.rays(0).size() + geom.rays(1).size();
            if (st.rayLum == null || st.rayLum.length != totalRays) {
                st.rayLum = new float[totalRays];
                java.util.Arrays.fill(st.rayLum, 0.5f);
            }
            int skyDarken = level.getSkyDarken();
            double dayFactor = 1.0 - skyDarken / 15.0;
            double rayLen = cfy(cfg.visionRayLength);
            int rayIdx = 0;
            for (int eyeSide = 0; eyeSide < 2; eyeSide++) {
                for (RetinaGeometry.Ray ray : geom.rays(eyeSide)) {
                    if ((rayIdx & 1) == (st.rayPhase & 1)) {
                        Vec3 dir = toWorld(ray.dx, ray.dy, ray.dz, yaw);
                        Vec3 to = eye.add(dir.scale(rayLen));
                        BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, fly));
                        float lum;
                        if (hit.getType() == HitResult.Type.MISS) {
                            lum = (float) (dir.y > -0.2 ? 0.85 * dayFactor + 0.05 : 0.25);
                        } else {
                            BlockPos hp = hit.getBlockPos();
                            BlockPos lp = hp.relative(hit.getDirection());
                            int bl = level.getBrightness(LightLayer.BLOCK, lp);
                            int sl = level.getBrightness(LightLayer.SKY, lp);
                            double light = Math.max(bl, sl * dayFactor) / 15.0;
                            int col = level.getBlockState(hp).getMapColor(level, hp).col;
                            double albedo = (0.30 * ((col >> 16) & 255) + 0.59 * ((col >> 8) & 255) + 0.11 * (col & 255)) / 255.0;
                            lum = (float) (light * (0.3 + 0.7 * albedo));
                        }
                        st.rayLum[rayIdx] = lum;
                    }
                    float lum = st.rayLum[rayIdx];
                    for (int ci : ray.columns) frame.luminance[ci] = lum;
                    rayIdx++;
                }
            }
            st.rayPhase++;
            // paint dark objects onto the columns they cover (entities are not raycast)
            for (SensoryFrame.VisualObject o : frame.objects) {
                if (o.angularSizeDeg < 2) continue;
                for (int ci : geom.columnsWithin(o.azimuthDeg, o.elevationDeg, o.angularSizeDeg / 2)) {
                    float cur = frame.luminance[ci];
                    frame.luminance[ci] = Float.isNaN(cur) ? 0.1f : Math.min(cur, 0.1f + 0.3f * (1 - o.contrast));
                }
            }
        }
    }

    private static double cfy(double v) { return Math.max(1, v); }

    private static void applyTaste(SensoryFrame frame, TasteTable.Taste t, boolean labellar) {
        for (Map.Entry<String, Float> e : t.tarsal().entrySet()) frame.addTaste(e.getKey(), e.getValue());
        if (labellar) for (Map.Entry<String, Float> e : t.labellar().entrySet()) frame.addTaste(e.getKey(), e.getValue());
    }

    /** Sum of appetitive vs aversive odor drive, for the reflex layer and HUD. */
    public static float[] odorValence(SensoryFrame f) {
        float good = 0, bad = 0;
        for (Map.Entry<String, Float> e : f.odor.entrySet()) {
            if (OdorTable.isAversive(e.getKey())) bad += e.getValue(); else good += e.getValue();
        }
        return new float[]{good, bad};
    }

    /** Visible list of odor keys for debugging. */
    public static List<String> describeOdor(SensoryFrame f) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Float> e : f.odor.entrySet()) if (e.getValue() > 0.02f) l.add(e.getKey() + "=" + String.format("%.2f", e.getValue()));
        return l;
    }
}
