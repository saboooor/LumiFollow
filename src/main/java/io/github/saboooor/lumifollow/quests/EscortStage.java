package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.players.PlayerQuester;
import fr.skytasul.quests.api.quests.Quest;
import fr.skytasul.quests.api.quests.branches.QuestBranch;
import fr.skytasul.quests.api.quests.branches.QuestBranchesManager;
import fr.skytasul.quests.api.questers.Quester;
import fr.skytasul.quests.api.stages.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import java.util.*;

/** A normal BeautyQuests stage: follows only while this objective is active. */
public final class EscortStage extends AbstractStage {
    static final String DEFAULT_BUSY_MESSAGE = "§eYour escort NPC is unavailable or busy. This objective will wait.";
    static final String DEFAULT_DIED_MESSAGE = "§cYour escort NPC was killed!";

    final String profileName;
    private final LumiFollowPlugin plugin;
    private EscortProfile profile;
    private final Map<Quester, Session> sessions = new HashMap<>();
    private static final class Session {
        NPC npc;
        Location startLocation;
        boolean cloned;
        boolean notified;
    }

    EscortStage(StageController controller, LumiFollowPlugin plugin, String profileName) {
        super(controller);
        this.plugin = plugin;
        this.profileName = profileName;
        this.profile = plugin.profile(profileName);
    }
    EscortProfile profile() { return profile; }
    int activeSessionCount() { return sessions.size(); }
    void reloadProfile() { this.profile = plugin.profile(profileName); }
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
        if (session.npc != null) {
            boolean ownsLease = plugin.leases.release(session.npc, session);
            if (ownsLease) {
                plugin.following.release(session.npc);
                if (session.cloned) {
                    session.npc.destroy();
                } else {
                    returnNpcOnEnd(session);
                }
            }
        }
        session.npc = null;
        session.startLocation = null;
        session.cloned = false;
    }
    private void returnNpcOnEnd(Session session) {
        if (profile.endPolicy() == EscortProfile.EndPolicy.STAY || !session.npc.isSpawned()) return;
        Location destination = null;
        if (profile.endPolicy() == EscortProfile.EndPolicy.RETURN_HOME && profile.home() != null) {
            destination = profile.home().toBukkitLocation();
        }
        if (destination == null) {
            destination = session.startLocation;
        }
        if (destination != null && destination.getWorld() != null) {
            session.npc.teleport(destination, TeleportCause.PLUGIN);
        }
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
            if (session.npc != null && profile.failOnDeath() && isDeadOrDespawned(session.npc)) {
                handleNpcDeath(quester, player);
                continue;
            }
            NPC npc = resolveTargetNpc(session);
            if (session.npc != null && session.npc != npc) release(session);
            if (npc == null || !npc.isSpawned() || !plugin.leases.acquire(npc, session)) {
                if (!session.notified) {
                    String busyMsg = plugin.npcBusyMessage();
                    player.sendMessage(busyMsg != null ? busyMsg : DEFAULT_BUSY_MESSAGE);
                    session.notified = true;
                }
                continue;
            }
            if (session.npc == null) {
                session.startLocation = snapshotLocation(npc);
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
    private NPC resolveTargetNpc(Session session) {
        NPC existing = CitizensAPI.getNPCRegistry().getById(profile.npcId());
        if (existing == null || !existing.isSpawned()) {
            plugin.ensureConfiguredNpcs();
            existing = CitizensAPI.getNPCRegistry().getById(profile.npcId());
        }
        if (profile.cloneNpc()) {
            if (session.npc != null && session.cloned) return session.npc;
            if (existing == null) return null;
            NPC cloned = existing.clone();
            if (cloned == null) return null;
            if (!cloned.isSpawned()) {
                Location spawnLoc = profile.home() != null ? profile.home().toBukkitLocation() : null;
                if (spawnLoc == null) spawnLoc = snapshotLocation(existing);
                if (spawnLoc != null) cloned.spawn(spawnLoc);
            }
            session.cloned = true;
            return cloned;
        }
        return existing;
    }
    private static Location snapshotLocation(NPC npc) {
        if (npc.isSpawned() && npc.getEntity() != null && npc.getEntity().getLocation() != null) {
            return npc.getEntity().getLocation().clone();
        }
        return npc.getStoredLocation();
    }
    private static boolean isDeadOrDespawned(NPC npc) {
        if (!npc.isSpawned() || npc.getEntity() == null) return true;
        return npc.getEntity() instanceof LivingEntity living && living.isDead();
    }
    private void handleNpcDeath(Quester quester, Player player) {
        String diedMsg = plugin.npcDiedMessage();
        player.sendMessage(diedMsg != null ? diedMsg : DEFAULT_DIED_MESSAGE);
        ended(quester);
        QuestBranch currentBranch = getController().getBranch();
        if (currentBranch == null) return;
        if (profile.failureBranch() >= 0 && currentBranch.getManager() != null) {
            QuestBranchesManager manager = currentBranch.getManager();
            QuestBranch targetBranch = manager.getBranch(profile.failureBranch());
            if (targetBranch != null && !targetBranch.getRegularStages().isEmpty()) {
                targetBranch.setPlayerStage(quester, targetBranch.getRegularStage(0));
                return;
            }
        }
        Quest quest = currentBranch.getQuest();
        if (quest != null) {
            quest.cancelQuester(quester);
        }
    }
    @Override public String getDefaultDescription(StageDescriptionPlaceholdersContext context) {
        String formatted = plugin.formatStageDescription(profile);
        if (formatted != null) return formatted;
        return "Escort NPC " + profile.npcId() + " to " + profile.world() + " ("
                + profile.x() + ", " + profile.y() + ", " + profile.z() + ")";
    }
}
