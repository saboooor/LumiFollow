package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.players.PlayerQuester;
import fr.skytasul.quests.api.questers.Quester;
import fr.skytasul.quests.api.stages.*;
import fr.skytasul.quests.api.stages.creation.StageCreation;
import fr.skytasul.quests.api.stages.options.StageOption;
import io.github.saboooor.lumifollow.FollowOptions;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;

/** Optional behavior on any quest objective; never advances or completes the objective. */
public final class FollowStageOption<T extends AbstractStage> extends StageOption<T> {
    private final LumiFollowPlugin plugin;
    private boolean enabled;
    private String npcData = "npc";
    private FollowOptions options = FollowOptions.defaults().withRecovery(true, true, false);
    private final Map<Quester, Session> sessions = new HashMap<>();
    private static final class Session { NPC npc; }

    FollowStageOption(Class<? extends T> stageClass, LumiFollowPlugin plugin) {
        super(stageClass);
        this.plugin = plugin;
    }
    @Override public FollowStageOption<T> clone() {
        var copy = new FollowStageOption<T>(getStageClass(), plugin);
        copy.enabled = enabled;
        copy.npcData = npcData;
        copy.options = options;
        return copy;
    }
    @Override public String getName() { return "LumiFollow NPC following"; }
    @Override public void startEdition(StageCreation<T> creation) { enabled = !enabled; }
    @Override public boolean shouldSave() { return enabled; }
    @Override public void save(ConfigurationSection section) {
        section.set("enabled", enabled);
        section.set("npc-data", npcData);
        section.set("open-doors", options.openDoors());
        section.set("climbing", options.climbing());
        section.set("max-climb-height", options.maxClimbHeight());
        section.set("climb-timeout-ticks", options.climbTimeoutTicks());
        section.set("teleport-recovery", options.teleportRecovery());
        section.set("follow-teleports", options.followTeleports());
        section.set("cross-world", options.crossWorld());
    }
    @Override public void load(ConfigurationSection section) {
        enabled = section.getBoolean("enabled", true);
        npcData = section.getString("npc-data", "npc");
        if (npcData == null || npcData.isBlank()) throw new IllegalArgumentException("LumiFollow npc-data must not be blank");
        options = FollowOptions.defaults().withDoors(section.getBoolean("open-doors", true))
                .withClimbing(section.getBoolean("climbing", true), section.getInt("max-climb-height", 6),
                        section.getInt("climb-timeout-ticks", 100))
                .withRecovery(section.getBoolean("teleport-recovery", true),
                        section.getBoolean("follow-teleports", true), section.getBoolean("cross-world", false));
    }
    @Override public void stageLoad(StageController controller) {
        super.stageLoad(controller);
        plugin.followOptions.add(this);
    }
    @Override public void stageUnload(StageController controller) {
        stopAll();
        plugin.followOptions.remove(this);
        super.stageUnload(controller);
    }
    @Override public void stageStart(Quester quester, StageController controller) {
        if (enabled && plugin.isEnabled()) sessions.computeIfAbsent(quester, ignored -> new Session());
    }
    @Override public void stageJoin(Player player, Quester quester, StageController controller) {
        if (controller.hasStarted(quester)) stageStart(quester, controller);
    }
    @Override public void stageEnd(Quester quester, StageController controller) {
        Session session = sessions.remove(quester);
        if (session != null) release(session);
    }
    @Override public void stageLeave(Player player, Quester quester, StageController controller) { stageEnd(quester, controller); }
    @Override public void stageUpdated(Quester quester, StageController controller) {
        if (enabled && plugin.isEnabled() && controller.hasStarted(quester)) {
            stageStart(quester, controller);
            follow(quester, sessions.get(quester), controller);
        }
    }
    private void release(Session session) {
        if (session.npc != null && plugin.leases.release(session.npc, session)) plugin.following.release(session.npc);
        session.npc = null;
    }
    void stopAll() {
        for (Session session : sessions.values()) release(session);
        sessions.clear();
    }
    void tick() {
        StageController controller = getStageController().orElse(null);
        if (controller == null || !enabled || !plugin.isEnabled()) { stopAll(); return; }
        for (var entry : List.copyOf(sessions.entrySet())) {
            if (!controller.hasStarted(entry.getKey())) stageEnd(entry.getKey(), controller);
            else follow(entry.getKey(), entry.getValue(), controller);
        }
    }
    private void follow(Quester quester, Session session, StageController controller) {
        Player player = quester instanceof PlayerQuester personal ? personal.getPlayer().orElse(null) : null;
        if (player == null || !player.isOnline() || player.isDead()) { release(session); return; }
        String value = controller.getData(quester, npcData, String.class);
        NPC npc = resolve(value);
        if (session.npc != null && session.npc != npc) release(session);
        if (npc == null || !npc.isSpawned()) { release(session); return; }
        if (!plugin.leases.acquire(npc, session)) return;
        session.npc = npc;
        plugin.following.follow(npc, player, options);
    }
    private NPC resolve(String value) {
        if (value == null) return null;
        UUID id;
        try { id = UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
        Entity entity = Bukkit.getEntity(id);
        if (entity == null) return null;
        // Temporary and named registries are supported as well as persistent Citizens NPCs.
        for (var registry : CitizensAPI.getNPCRegistries()) {
            NPC npc = registry.getNPC(entity);
            if (npc != null) return npc;
        }
        return null;
    }
}
