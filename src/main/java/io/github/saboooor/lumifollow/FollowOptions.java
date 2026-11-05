package io.github.saboooor.lumifollow;

/** Immutable pursuit policy. Speeds are Citizens multipliers, distances are blocks. */
public record FollowOptions(float walkSpeed, float sprintSpeed, double stopDistance,
                            float range, int updatePathTicks, int stationaryTicks,
                            boolean openDoors, boolean climbing, int maxClimbHeight,
                            int climbTimeoutTicks, boolean teleportRecovery,
                            boolean followTeleports, boolean crossWorld,
                            boolean teleportSound) {
    public FollowOptions {
        if (!Float.isFinite(walkSpeed) || walkSpeed <= 0 || !Float.isFinite(sprintSpeed) || sprintSpeed <= 0
                || !Double.isFinite(stopDistance) || stopDistance < 0
                || !Float.isFinite(range) || range <= stopDistance
                || updatePathTicks < 1 || stationaryTicks < 1
                || maxClimbHeight < 1 || maxClimbHeight > 16 || climbTimeoutTicks < 1) {
            throw new IllegalArgumentException("Invalid follow options");
        }
    }
    public static FollowOptions defaults() {
        return new FollowOptions(1.15F, 1.5F, 2.5, 64, 10, 20, true, true, 6, 100,
                true, true, true, true);
    }
    public FollowOptions withDoors(boolean enabled) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                enabled, climbing, maxClimbHeight, climbTimeoutTicks, teleportRecovery, followTeleports, crossWorld, teleportSound);
    }
    public FollowOptions withClimbing(boolean enabled, int height, int timeout) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                openDoors, enabled, height, timeout, teleportRecovery, followTeleports, crossWorld, teleportSound);
    }
    public FollowOptions withRecovery(boolean teleport, boolean followTeleports, boolean crossWorld) {
        return new FollowOptions(walkSpeed, sprintSpeed, stopDistance, range, updatePathTicks, stationaryTicks,
                openDoors, climbing, maxClimbHeight, climbTimeoutTicks, teleport, followTeleports, crossWorld, teleportSound);
    }
}
