package io.github.saboooor.lumifollow;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.util.Objects;

/** Reusable follow session for a caller-owned Citizens NPC; stop pauses, close disposes. */
public final class FollowController implements AutoCloseable {
    private final Plugin plugin;
    private final NPC npc;
    private final FollowOptions options;
    private final WallClimber climber;
    private BukkitTask task;
    private Player target;
    private boolean closed, climbing;
    private int retryTicks, stuckTicks, recoveryTicks;
    private Location lastLocation, previousTargetLocation;

    FollowController(Plugin plugin, NPC npc, FollowOptions options) {
        this.plugin = plugin;
        this.npc = npc;
        this.options = options;
        this.climber = new WallClimber(options.openDoors());
    }
    public FollowOptions options() { return options; }
    public Player target() { return target; }
    public boolean isFollowing() { return target != null && !closed; }
    boolean follows(Player player) { return target != null && target.getUniqueId().equals(player.getUniqueId()); }
    private boolean valid() {
        return npc.isSpawned() && npc.getEntity() instanceof LivingEntity living && living.isValid();
    }
    private LivingEntity entity() { return (LivingEntity) npc.getEntity(); }

    /** Starts or retargets one session. Repeated calls with the same player do not restart navigation. */
    public boolean follow(Player player) {
        Objects.requireNonNull(player);
        if (closed || !valid() || !player.isOnline() || player.isDead()) return false;
        if (follows(player) && task != null) return true;
        stop();
        target = player;
        previousTargetLocation = player.getLocation();
        Navigator navigator = npc.getNavigator();
        navigator.getDefaultParameters().range(options.range()).speedModifier(options.walkSpeed())
                .distanceMargin(Math.min(2, options.stopDistance())).useNewPathfinder(true)
                .updatePathRate(options.updatePathTicks()).stationaryTicks(options.stationaryTicks())
                .stuckAction((ignoredNpc, ignoredNavigator) -> options.teleportRecovery() && target != null && recover());
        npc.data().setPersistent("pathfinder-open-doors", options.openDoors());
        npc.data().setPersistent("activation-range", (int) Math.ceil(options.range()));
        if (options.openDoors() && !navigator.getDefaultParameters().hasExaminer(DoorExaminer.class)) {
            navigator.getDefaultParameters().examiner(new DoorExaminer());
        }
        navigator.setTarget(player, false);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    void tick() {
        if (closed || !valid() || target == null || !target.isOnline() || target.isDead()) { stop(); return; }
        if (recoveryTicks > 0) recoveryTicks--;
        Location here = entity().getLocation(), goal = target.getLocation();
        if (!here.getWorld().equals(goal.getWorld())) {
            if (!options.crossWorld()) { stop(); return; }
            if (options.teleportRecovery()) recover();
            return;
        }
        double distance = here.distanceSquared(goal);
        if (distance > options.range() * options.range() && options.teleportRecovery()) {
            recover();
            return;
        }
        if (distance <= options.stopDistance() * options.stopDistance()) {
            if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
            resetMotion();
            previousTargetLocation = goal;
            return;
        }
        if (lastLocation != null && here.distanceSquared(lastLocation) < 0.09) {
            if (++stuckTicks >= 50 && options.teleportRecovery()) {
                stuckTicks = 0;
                recover();
                return;
            }
        } else { stuckTicks = 0; lastLocation = here.clone(); }
        boolean sprinting = !climbing && runningAway(here, previousTargetLocation, goal, target.isSprinting());
        previousTargetLocation = goal;
        float speed = sprinting ? options.sprintSpeed() : options.walkSpeed();
        Navigator navigator = npc.getNavigator();
        navigator.getDefaultParameters().speedModifier(speed);
        if (navigator.isNavigating()) navigator.getLocalParameters().speedModifier(speed);
        if (entity() instanceof Player body) body.setSprinting(sprinting);
        if (options.openDoors()) climber.openDoorsInWay(entity(), target);
        boolean wasClimbing = climbing;
        climbing = options.climbing() && climber.tick(entity(), target, options.maxClimbHeight(),
                options.climbTimeoutTicks(), navigator::cancelNavigation);
        if (!climbing && !navigator.isNavigating()) {
            climber.approachWhenNoPath(entity(), target, sprinting ? 0.235 : 0.18);
        }
        if (!climbing && (wasClimbing || ++retryTicks >= options.updatePathTicks())) {
            retryTicks = 0;
            if (wasClimbing || !navigator.isNavigating()) navigator.setTarget(target, false);
        }
    }
    static boolean runningAway(Location pursuer, Location previous, Location current, boolean sprinting) {
        if (!sprinting || previous == null || !current.getWorld().equals(previous.getWorld())
                || !current.getWorld().equals(pursuer.getWorld())) return false;
        double dx = current.getX() - previous.getX(), dz = current.getZ() - previous.getZ();
        return dx * dx + dz * dz > 0.0001
                && dx * (current.getX() - pursuer.getX()) + dz * (current.getZ() - pursuer.getZ()) > 0;
    }
    private boolean recover() {
        if (recoveryTicks > 0 || target == null) return false;
        recoveryTicks = 20;
        return teleportCloser(target);
    }
    /** Explicit recovery obeys teleport and world policies. Returns false when no safe landing exists. */
    public boolean teleportCloser(Player player) {
        if (closed || !valid() || player == null || !player.isOnline() || !options.teleportRecovery()) return false;
        if (!options.crossWorld() && !entity().getWorld().equals(player.getWorld())) return false;
        Location origin = player.getLocation();
        Location safe = SafeLocations.findNear(origin, 4, 8);
        if (safe == null) safe = SafeLocations.findNear(origin, 2, 5);
        if (safe == null) return false;
        Location old = entity().getLocation();
        if (!teleport(safe)) return false;
        if (options.teleportSound()) {
            old.getWorld().playSound(old, "minecraft:entity.enderman.teleport", 1F, 1F);
            safe.getWorld().playSound(safe, "minecraft:entity.enderman.teleport", 1F, 1F);
        }
        if (target != null) npc.getNavigator().setTarget(target, false);
        return true;
    }
    /** Explicit caller-supplied destination; caller is responsible for its safety. */
    public boolean teleport(Location location) {
        if (closed || !valid() || location == null || location.getWorld() == null) return false;
        if (!options.crossWorld() && !entity().getWorld().equals(location.getWorld())) return false;
        npc.getNavigator().cancelNavigation();
        npc.teleport(location, TeleportCause.PLUGIN);
        if (!valid() || !entity().getWorld().equals(location.getWorld())
                || entity().getLocation().distanceSquared(location) > 0.01) return false;
        entity().setVelocity(new Vector());
        entity().setFallDistance(0);
        resetMotion();
        return true;
    }
    void onChunkUnload(ChunkUnloadEvent event) {
        if (!isFollowing() || !valid()) return;
        Location location = entity().getLocation();
        if (location.getWorld().equals(event.getWorld()) && (location.getBlockX() >> 4) == event.getChunk().getX()
                && (location.getBlockZ() >> 4) == event.getChunk().getZ()) {
            if (!teleportCloser(target)) stop();
        }
    }
    private void resetMotion() {
        climber.reset(); climbing = false; retryTicks = 0; stuckTicks = 0; lastLocation = null;
        if (valid() && entity() instanceof Player body) body.setSprinting(false);
    }
    public void stop() {
        if (task != null) task.cancel();
        task = null; target = null; previousTargetLocation = null;
        resetMotion();
        if (npc.getNavigator() != null) npc.getNavigator().cancelNavigation();
    }
    @Override public void close() { if (!closed) { stop(); closed = true; } }
}
