package io.github.saboooor.lumifollow;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** One service per owning plugin. All calls must run on the server main thread. */
public final class LumiFollow implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Map<NPC, FollowController> controllers = new IdentityHashMap<>();
    private boolean closed;
    private final Set<UUID> pendingTeleports = new HashSet<>();

    public LumiFollow(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Returns the existing controller for this NPC; its original options remain in effect until released. */
    public FollowController controller(NPC npc, FollowOptions options) {
        if (closed) throw new IllegalStateException("Following service is closed");
        Objects.requireNonNull(npc);
        Objects.requireNonNull(options);
        return controllers.computeIfAbsent(npc, key -> new FollowController(plugin, key, options));
    }
    public FollowController follow(NPC npc, Player target, FollowOptions options) {
        FollowController controller = controller(npc, options);
        controller.follow(target);
        return controller;
    }
    public void stop(NPC npc) {
        FollowController controller = controllers.get(npc);
        if (controller != null) controller.stop();
    }
    /** Release before destroying an NPC. The library never destroys caller-owned NPCs. */
    public void release(NPC npc) {
        FollowController controller = controllers.remove(npc);
        if (controller != null) controller.close();
    }
    private void stopTarget(Player player) {
        for (FollowController controller : controllers.values()) {
            if (controller.follows(player)) controller.stop();
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { stopTarget(event.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { stopTarget(event.getEntity()); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) afterTeleport(event.getPlayer());
    }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { afterTeleport(event.getPlayer()); }
    private void afterTeleport(Player player) {
        // One deferred recovery for teleport + world-change events in the same tick.
        if (closed || controllers.values().stream().noneMatch(controller -> controller.follows(player))
                || !pendingTeleports.add(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingTeleports.remove(player.getUniqueId());
            if (closed) return;
            for (FollowController controller : controllers.values()) {
                if (controller.follows(player) && controller.options().followTeleports()) controller.teleportCloser(player);
            }
        });
    }
    @EventHandler(ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (FollowController controller : controllers.values()) {
            controller.onChunkUnload(event);
        }
    }
    @EventHandler public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin || event.getPlugin().getName().equals("Citizens")) close();
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        controllers.values().forEach(FollowController::close);
        controllers.clear();
        pendingTeleports.clear();
        HandlerList.unregisterAll(this);
    }
}
