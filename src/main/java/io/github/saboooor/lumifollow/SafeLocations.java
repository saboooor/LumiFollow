package io.github.saboooor.lumifollow;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import java.util.concurrent.ThreadLocalRandom;

/** Loaded-chunk-only landing search shared by spawning and recovery. */
public final class SafeLocations {
    private SafeLocations() {}
    public static Location findNear(Location origin, double minRadius, double maxRadius) {
        if (!Double.isFinite(minRadius) || !Double.isFinite(maxRadius) || minRadius < 0 || maxRadius < minRadius)
            throw new IllegalArgumentException("Invalid search radii");
        World world = origin.getWorld();
        if (world == null) return null;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = random.nextDouble(Math.PI * 2);
            double radius = random.nextDouble(minRadius, maxRadius + 1.0);
            int x = (int) Math.floor(origin.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(origin.getZ() + Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            // Search near the player's elevation so caves and buildings work too.
            for (int offset : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4}) {
                int y = origin.getBlockY() + offset;
                if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
                Block floor = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (!floor.isSolid() || !safeBlock(floor) || !feet.isPassable()
                        || !head.isPassable() || !safeBlock(feet) || !safeBlock(head)) continue;
                Location result = new Location(world, x + 0.5, y, z + 0.5);
                if (world.getWorldBorder().isInside(result)) {
                    org.bukkit.util.Vector lookDir = origin.toVector().subtract(result.toVector()).setY(0);
                    if (lookDir.lengthSquared() > 0.01) result.setDirection(lookDir);
                    return result;
                }
            }
        }
        return null;
    }

    public static boolean safeBlock(Block block) {
        return !block.isLiquid() && switch (block.getType()) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE,
                    CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW, WITHER_ROSE,
                    NETHER_PORTAL, END_PORTAL, COBWEB -> false;
            default -> true;
        };
    }

}
