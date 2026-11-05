package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.players.PlayerQuester;
import fr.skytasul.quests.api.questers.Quester;
import fr.skytasul.quests.api.stages.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import java.util.*;

/** A normal BeautyQuests stage: follows only while this objective is active. */
public final class EscortStage extends AbstractStage {
    final String profileName;
    private final LumiFollowPlugin plugin;
    private final EscortProfile profile;
    private final Map<Quester, Session> sessions = new HashMap<>();
    private static final class Session { NPC npc; boolean notified; }

    EscortStage(StageController controller, LumiFollowPlugin plugin, String profileName) {
        super(controller);
        this.plugin = plugin;
        this.profileName = profileName;
        this.profile = plugin.profile(profileName);
    }
    @Override public void load() { super.load(); plugin.stages.add(this); }
    @Override public void unload() { stopAll(); plugin.stages.remove(this); super.unload(); }
    @Override protected void serialize(ConfigurationSection section) {
        super.serialize(section);
        section.set("profile", profileName);
    }
    @Override public void started(Quester quester) {
        plugin.stages.add(this);
        sessions.computeIfAbsent(quester, ignored -> new Session());
    }
    @Override public void joined(Player player, Quester quester) {
        if (getController().hasStarted(quester)) started(quester);
    }
    @Override public void left(Player player, Quester quester) { ended(quester); }
    @Override public void ended(Quester quester) {
        Session session = sessions.remove(quester);
        if (session != null) release(session);
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
        for (var entry : List.copyOf(sessions.entrySet())) {
            Quester quester = entry.getKey();
            Session session = entry.getValue();
            if (!getController().hasStarted(quester)) { ended(quester); continue; }
            Player player = quester instanceof PlayerQuester personal ? personal.getPlayer().orElse(null) : null;
            if (player == null || !player.isOnline() || player.isDead()) { release(session); continue; }
            NPC npc = CitizensAPI.getNPCRegistry().getById(profile.npcId());
            if (session.npc != null && session.npc != npc) release(session);
            if (npc == null || !npc.isSpawned() || !plugin.leases.acquire(npc, session)) {
                if (!session.notified) {
                    player.sendMessage("§eYour escort NPC is unavailable or busy. This objective will wait.");
                    session.notified = true;
                }
                continue;
            }
            session.npc = npc;
            session.notified = false;
            plugin.following.follow(npc, player, profile.options());
            if (profile.arrived(player.getLocation()) && profile.arrived(npc.getEntity().getLocation())
                    && matchesRequirements(player, false)) {
                ended(quester);
                finishStage(quester);
            }
        }
    }
    @Override public String getDefaultDescription(StageDescriptionPlaceholdersContext context) {
        return "Escort NPC " + profile.npcId() + " to " + profile.world() + " ("
                + profile.x() + ", " + profile.y() + ", " + profile.z() + ")";
    }
}
