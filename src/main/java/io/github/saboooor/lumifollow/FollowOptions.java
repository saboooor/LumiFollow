package io.github.saboooor.lumifollow;

import org.bukkit.configuration.ConfigurationSection;

/** Immutable pursuit policy. Speeds are Citizens multipliers, distances are blocks. */
public record FollowOptions(float walkSpeed, float sprintSpeed, double stopDistance,
                            float range, int updatePathTicks, int stationaryTicks,
                            boolean openDoors, boolean climbing, int maxClimbHeight,
                            int climbTimeoutTicks, boolean teleportRecovery,
                            boolean followTeleports, boolean crossWorld,
                            boolean teleportSound, int closeDoorsDelayTicks,
                            boolean mountVehicles) {
    public FollowOptions {
        if (!Float.isFinite(walkSpeed) || walkSpeed <= 0 || !Float.isFinite(sprintSpeed) || sprintSpeed <= 0
                || !Double.isFinite(stopDistance) || stopDistance < 0
                || !Float.isFinite(range) || range <= stopDistance
                || updatePathTicks < 1 || stationaryTicks < 1
                || maxClimbHeight < 1 || maxClimbHeight > 16 || climbTimeoutTicks < 1
                || closeDoorsDelayTicks < 0) {
            throw new IllegalArgumentException("Invalid follow options");
        }
    }
    public FollowOptions(float walkSpeed, float sprintSpeed, double stopDistance,
                         float range, int updatePathTicks, int stationaryTicks,
                         boolean openDoors, boolean climbing, int maxClimbHeight,
                         int climbTimeoutTicks, boolean teleportRecovery,
                         boolean followTeleports, boolean crossWorld,
                         boolean teleportSound) {
        this(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                openDoors, climbing, maxClimbHeight, climbTimeoutTicks, teleportRecovery,
                followTeleports, crossWorld, teleportSound, 0, false);
    }
    public static FollowOptions defaults() {
        return new FollowOptions(1.15F, 1.5F, 2.5, 64, 10, 20, true, true, 6, 100,
                true, true, true, true, 0, false);
    }
    public FollowOptions withNavigation(float walk, float sprint, double stop, float maxRange,
                                        int updateTicks, int stuckTicks) {
        return new FollowOptions(walk, sprint, stop, maxRange, updateTicks, stuckTicks,
                openDoors, climbing, maxClimbHeight, climbTimeoutTicks, teleportRecovery,
                followTeleports, crossWorld, teleportSound, closeDoorsDelayTicks, mountVehicles);
    }
    public FollowOptions withDoors(boolean enabled) {
        return withDoors(enabled, closeDoorsDelayTicks);
    }
    public FollowOptions withDoors(boolean enabled, int closeDelayTicks) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                enabled, climbing, maxClimbHeight, climbTimeoutTicks, teleportRecovery, followTeleports,
                crossWorld, teleportSound, closeDelayTicks, mountVehicles);
    }
    public FollowOptions withClimbing(boolean enabled, int height, int timeout) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                openDoors, enabled, height, timeout, teleportRecovery, followTeleports,
                crossWorld, teleportSound, closeDoorsDelayTicks, mountVehicles);
    }
    public FollowOptions withRecovery(boolean teleport, boolean followTeleports, boolean crossWorld) {
        return withRecovery(teleport, followTeleports, crossWorld, teleportSound);
    }
    public FollowOptions withRecovery(boolean teleport, boolean followTeleports, boolean crossWorld, boolean sound) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                openDoors, climbing, maxClimbHeight, climbTimeoutTicks, teleport, followTeleports,
                crossWorld, sound, closeDoorsDelayTicks, mountVehicles);
    }
    public FollowOptions withVehicles(boolean enabled) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                openDoors, climbing, maxClimbHeight, climbTimeoutTicks, teleportRecovery, followTeleports,
                crossWorld, teleportSound, closeDoorsDelayTicks, enabled);
    }
    public static FollowOptions read(ConfigurationSection section, boolean defaultOpenDoors, boolean defaultCrossWorld) {
        FollowOptions base = defaults();
        if (section == null) {
            return base.withDoors(defaultOpenDoors).withRecovery(true, true, defaultCrossWorld);
        }
        float walk = (float) section.getDouble("walk-speed", base.walkSpeed());
        float sprint = (float) section.getDouble("sprint-speed", base.sprintSpeed());
        double stop = section.getDouble("stop-distance", base.stopDistance());
        float maxRange = (float) section.getDouble("range", base.range());
        int updateTicks = section.getInt("update-path-ticks", base.updatePathTicks());
        int stuckTicks = section.getInt("stationary-ticks", base.stationaryTicks());
        boolean doors = section.getBoolean("open-doors", defaultOpenDoors);
        int closeDelay = section.getInt("close-doors-delay-ticks", base.closeDoorsDelayTicks());
        boolean climb = section.getBoolean("climbing", base.climbing());
        int climbHeight = section.getInt("max-climb-height", base.maxClimbHeight());
        int climbTimeout = section.getInt("climb-timeout-ticks", base.climbTimeoutTicks());
        boolean teleport = section.getBoolean("teleport-recovery", base.teleportRecovery());
        boolean followTp = section.getBoolean("follow-teleports", base.followTeleports());
        boolean cross = section.getBoolean("cross-world", defaultCrossWorld);
        boolean sound = section.getBoolean("teleport-sound", base.teleportSound());
        boolean vehicles = section.getBoolean("mount-vehicles", base.mountVehicles());
        return new FollowOptions(walk, sprint, stop, maxRange, updateTicks, stuckTicks,
                doors, climb, climbHeight, climbTimeout, teleport, followTp, cross, sound, closeDelay, vehicles);
    }
}
