package io.github.saboooor.lumifollow;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;

/** A bounded velocity assist; Citizens remains responsible for ordinary navigation. */
final class WallClimber {
    private final boolean openDoors;

    WallClimber() { this(true); }
    WallClimber(boolean openDoors) { this.openDoors = openDoors; }

    private Location landing;
    private int ticks;
    private boolean exhausted;
    private boolean dropping;

    void reset() {
        landing = null;
        ticks = 0;
        exhausted = false;
        dropping = false;
    }

    boolean tick(LivingEntity entity, Player target, int maxHeight, int timeoutTicks) {
        return tick(entity, target, maxHeight, timeoutTicks, () -> {});
    }

    boolean tick(LivingEntity entity, Player target, int maxHeight, int timeoutTicks, Runnable beforeMove) {
        Location here = entity.getLocation();
        if (!here.getWorld().equals(target.getWorld())) return false;

        if (dropping) {
            if (++ticks > timeoutTicks) {
                dropping = false;
                return false;
            }
            if (entity.isOnGround() && (ticks > 2 || here.getY() <= target.getLocation().getY() + 1.0)) {
                dropping = false;
                return false;
            }
            Vector step = target.getLocation().toVector().subtract(here.toVector()).setY(0);
            if (step.lengthSquared() > 0.01) step.normalize().multiply(0.2);
            beforeMove.run();
            Vector vel = entity.getVelocity();
            double vy = vel != null ? vel.getY() : 0.0;
            entity.setVelocity(new Vector(step.getX(), Math.min(vy, -0.1), step.getZ()));
            entity.setFallDistance(0);
            return true;
        }

        if (landing == null) {
            if (exhausted) {
                if (entity.isOnGround()) exhausted = false;
                return false;
            }
            Vector direction = target.getLocation().toVector().subtract(here.toVector()).setY(0);
            if (direction.lengthSquared() < 0.01) return false;
            direction.normalize();

            double dy = here.getY() - target.getLocation().getY();
            if (dy > 0.5) {
                Location stepLoc = here.clone().add(direction.clone().multiply(0.8));
                if (clear(stepLoc) && isSafeDrop(stepLoc, target.getLocation(), maxHeight)) {
                    dropping = true;
                    ticks = 0;
                    beforeMove.run();
                    entity.setVelocity(new Vector(direction.getX() * 0.22, -0.15, direction.getZ() * 0.22));
                    entity.setFallDistance(0);
                    return true;
                }
            }

            Location wall = here.clone().add(direction.clone().multiply(0.8));
            if (wall.getBlockX() == here.getBlockX() && wall.getBlockZ() == here.getBlockZ()) return false;
            if (!wall.getWorld().isChunkLoaded(wall.getBlockX() >> 4, wall.getBlockZ() >> 4)) return false;
            Block wallBlock = wall.getBlock();
            if (openDoors && (isOpenable(wallBlock) || isOpenable(wallBlock.getRelative(BlockFace.UP)))) {
                if (isOpenable(wallBlock)) openDoor(wallBlock, entity);
                else openDoor(wallBlock.getRelative(BlockFace.UP), entity);
                return false;
            }
            if (wallBlock.isPassable() || wallBlock.isLiquid()) return false;
            for (int height = 1; height <= maxHeight; height++) {
                Location candidate = new Location(here.getWorld(), wall.getBlockX() + 0.5,
                        here.getBlockY(), wall.getBlockZ() + 0.5);
                candidate.setY(here.getBlockY() + height);
                if (candidate.getY() + 2 >= here.getWorld().getMaxHeight()) break;
                if (clear(candidate) && !candidate.clone().subtract(0, 1, 0).getBlock().isPassable()
                        && !candidate.clone().subtract(0, 1, 0).getBlock().isLiquid()) {
                    landing = candidate.getBlock().getLocation().add(0.5, 0, 0.5);
                    ticks = 0;
                    break;
                }
            }
            if (landing == null) return false;
        }

        if (++ticks > timeoutTicks || !clear(landing)) {
            landing = null;
            exhausted = true;
            return false;
        }
        Vector across = landing.toVector().subtract(here.toVector()).setY(0);
        if (here.getY() >= landing.getY() && across.lengthSquared() < 0.16) {
            landing = null;
            exhausted = true;
            return false;
        }
        // Ascend beside the wall, then move over its edge. Never move through a ceiling.
        if (here.getY() < landing.getY() + 0.1) {
            if (!clear(here.clone().add(0, 0.35, 0))) {
                landing = null;
                exhausted = true;
                return false;
            }
            beforeMove.run();
            entity.setVelocity(new Vector(0, 0.25, 0));
        } else {
            if (across.lengthSquared() > 0.01) across.normalize().multiply(0.18);
            beforeMove.run();
            entity.setVelocity(across.setY(0.05));
        }
        entity.setFallDistance(0);
        return true;
    }

    void approachWhenNoPath(LivingEntity entity, Player target) {
        approachWhenNoPath(entity, target, 0.18);
    }

    void approachWhenNoPath(LivingEntity entity, Player target, double speed) {
        Location here = entity.getLocation();
        if (!here.getWorld().equals(target.getWorld())) return;
        Vector step = target.getLocation().toVector().subtract(here.toVector()).setY(0);
        double dy = here.getY() - target.getLocation().getY();
        if (step.lengthSquared() <= 6.25 && dy <= 1.5 && dy >= -1.5) return;
        if (step.lengthSquared() < 0.01) return;
        if (!entity.isOnGround() && dy <= 0.5) return;
        step.normalize().multiply(speed);
        Location next = here.clone().add(step);
        Location floor = next.clone().subtract(0, 0.1, 0);
        if (openDoors && !clear(next)) {
            openDoorsInWay(entity, target);
        }
        if (clear(next)) {
            Vector vel = entity.getVelocity();
            double vy = vel != null ? vel.getY() : 0.0;
            if (!floor.getBlock().isPassable() && !floor.getBlock().isLiquid()) {
                entity.setVelocity(step.setY(vy));
            } else if (dy > 0.5 && isSafeDrop(next, target.getLocation(), 16)) {
                entity.setVelocity(new Vector(step.getX(), Math.min(vy, -0.15), step.getZ()));
                entity.setFallDistance(0);
            }
        }
    }

    boolean isSafeDrop(Location next, Location targetLoc, int maxHeight) {
        Location floor = next.clone().subtract(0, 0.1, 0);
        if (!floor.getBlock().isPassable()) return false;
        int targetY = targetLoc.getBlockY();
        int maxDrop = Math.max(maxHeight, 16);
        int minY = Math.max(targetY - 2, next.getBlockY() - maxDrop);
        minY = Math.max(minY, next.getWorld().getMinHeight());
        for (int y = next.getBlockY() - 1; y >= minY; y--) {
            Location sample = new Location(next.getWorld(), next.getX(), y, next.getZ());
            if (!sample.getWorld().isChunkLoaded(sample.getBlockX() >> 4, sample.getBlockZ() >> 4)) return false;
            Block block = sample.getBlock();
            if (!block.isPassable()) {
                return !block.isLiquid() && isSafeBlock(block);
            }
        }
        return false;
    }

    private boolean isSafeBlock(Block block) {
        return switch (block.getType()) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE,
                    CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW, WITHER_ROSE,
                    NETHER_PORTAL, END_PORTAL -> false;
            default -> true;
        };
    }

    private boolean clear(Location feet) {
        // Check the player's full width and height, including edges between blocks.
        for (double x : new double[]{-0.3, 0.3}) {
            for (double z : new double[]{-0.3, 0.3}) {
                for (double y : new double[]{0, 0.9, 1.8}) {
                    Location sample = feet.clone().add(x, y, z);
                    if (!sample.getWorld().isChunkLoaded(sample.getBlockX() >> 4, sample.getBlockZ() >> 4)
                            || !sample.getBlock().isPassable() || sample.getBlock().isLiquid()) return false;
                }
            }
        }
        return true;
    }

    boolean openDoorsInWay(LivingEntity entity, Player target) {
        Location here = entity.getLocation();
        if (!here.getWorld().equals(target.getWorld())) return false;
        Vector toTarget = target.getLocation().toVector().subtract(here.toVector()).setY(0);
        if (toTarget.lengthSquared() < 0.01) return false;
        Vector dir = toTarget.clone().normalize();
        Vector perp = new Vector(-dir.getZ(), 0, dir.getX());

        Set<Block> checked = new HashSet<>();
        boolean openedAny = false;
        for (double dist : new double[]{0.0, 0.6, 1.2, 1.8, 2.4}) {
            for (double lateral : new double[]{0.0, -0.4, 0.4}) {
                Location sample = here.clone().add(dir.clone().multiply(dist)).add(perp.clone().multiply(lateral));
                if (!sample.getWorld().isChunkLoaded(sample.getBlockX() >> 4, sample.getBlockZ() >> 4)) continue;
                for (int dy : new int[]{0, 1, -1}) {
                    Block block = sample.getWorld().getBlockAt(sample.getBlockX(), sample.getBlockY() + dy, sample.getBlockZ());
                    if (checked.add(block) && isOpenable(block) && openDoor(block, entity)) {
                        openedAny = true;
                    }
                }
            }
        }
        return openedAny;
    }

    static boolean isOpenable(Block block) {
        if (block == null) return false;
        try {
            return block.getBlockData() instanceof Openable;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean openDoor(Block block, LivingEntity entity) {
        if (block == null) return false;
        try {
            if (block.getBlockData() instanceof Openable openable && !openable.isOpen()) {
                openable.setOpen(true);
                block.setBlockData(openable, true);
                if (openable instanceof Door door) {
                    Block otherHalf = door.getHalf() == Bisected.Half.BOTTOM
                            ? block.getRelative(BlockFace.UP)
                            : block.getRelative(BlockFace.DOWN);
                    if (otherHalf != null && otherHalf.getBlockData() instanceof Openable other && !other.isOpen()) {
                        other.setOpen(true);
                        otherHalf.setBlockData(other, true);
                        try {
                            otherHalf.getState().update();
                        } catch (Throwable ignored) {}
                    }
                }
                try {
                    block.getState().update();
                } catch (Throwable ignored) {}
                playDoorSound(entity, block);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void playDoorSound(LivingEntity entity, Block block) {
        try {
            Material mat = block.getType();
            String name = mat != null ? mat.name() : "";
            Sound sound;
            if (name.contains("IRON_DOOR")) sound = Sound.BLOCK_IRON_DOOR_OPEN;
            else if (name.contains("IRON_TRAPDOOR")) sound = Sound.BLOCK_IRON_TRAPDOOR_OPEN;
            else if (name.contains("COPPER_DOOR")) sound = Sound.BLOCK_COPPER_DOOR_OPEN;
            else if (name.contains("COPPER_TRAPDOOR")) sound = Sound.BLOCK_COPPER_TRAPDOOR_OPEN;
            else if (name.contains("TRAPDOOR")) sound = Sound.BLOCK_WOODEN_TRAPDOOR_OPEN;
            else if (name.contains("GATE")) sound = Sound.BLOCK_FENCE_GATE_OPEN;
            else sound = Sound.BLOCK_WOODEN_DOOR_OPEN;
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), sound, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        if (entity != null) {
            try {
                entity.swingMainHand();
            } catch (Throwable ignored) {
            }
        }
    }
}
