package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.stages.*;
import fr.skytasul.quests.api.stages.options.*;
import fr.skytasul.quests.api.stages.creation.StageCreation;
import fr.skytasul.quests.api.stages.creation.StageGuiLine;
import io.github.saboooor.lumifollow.LumiFollow;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.CitizensReloadEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LumiFollowPlugin extends JavaPlugin implements Listener {
    final Set<EscortStage> stages = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<FollowStageOption<?>> followOptions = Collections.newSetFromMap(new IdentityHashMap<>());
    final NpcLeases leases = new NpcLeases();
    LumiFollow following;
    private BukkitTask task;

    @Override public void onEnable() {
        saveDefaultConfig();
        following = new LumiFollow(this);
        getServer().getPluginManager().registerEvents(this, this);
        // Eagerly initialize EscortProfile classes and any configured Citizens NPCs on enable
        Class<?> preloadProfile = EscortProfile.class;
        Class<?> preloadPolicy = EscortProfile.EndPolicy.class;
        Class<?> preloadHome = EscortProfile.HomeLocation.class;
        if (preloadProfile != null && preloadPolicy != null && preloadHome != null) {
            ensureConfiguredNpcs();
        }
        PluginCommand command = getCommand("lumifollow");
        if (command != null) {
            LumiFollowCommand executor = new LumiFollowCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        registerFollowOption();
        QuestsAPI.getAPI().getStages().register(new StageType<>("lumi-follow", EscortStage.class,
                "LumiFollow escort", (section, controller) -> new EscortStage(controller, this,
                        section.getString("profile", "default")), new ItemStack(Material.LEAD),
                context -> new StageCreation<EscortStage>(context) {
                    private static final int SLOT_PROFILE = 6;
                    private String profile = "default";
                    @Override public void setupLine(StageGuiLine line) {
                        super.setupLine(line);
                        line.setItem(SLOT_PROFILE, new ItemStack(Material.NAME_TAG), event -> {
                            List<String> profiles = availableProfiles();
                            if (!profiles.isEmpty()) {
                                int current = profiles.indexOf(profile);
                                int delta = event.getClick() != null && event.getClick().isRightClick() ? -1 : 1;
                                int next = Math.floorMod(current + delta, profiles.size());
                                profile = profiles.get(next);
                            }
                            line.refreshItemLoreOptionValue(SLOT_PROFILE, profile);
                            if (event.getPlayer() != null) {
                                event.getPlayer().sendMessage("§aSelected LumiFollow escort profile: §e" + profile);
                            }
                        });
                        line.refreshItemLoreOptionValue(SLOT_PROFILE, profile);
                    }
                    @Override public void edit(EscortStage stage) {
                        super.edit(stage);
                        profile = stage.profileName;
                        if (getLine() != null) {
                            getLine().refreshItemLoreOptionValue(SLOT_PROFILE, profile);
                        }
                    }
                    @Override protected EscortStage finishStage(StageController controller) {
                        return new EscortStage(controller, LumiFollowPlugin.this, profile);
                    }
                }));
        task = getServer().getScheduler().runTaskTimer(this, () -> {
            for (FollowStageOption<?> option : List.copyOf(followOptions)) {
                try { option.tick(); }
                catch (RuntimeException exception) {
                    option.stopAll();
                    getLogger().log(java.util.logging.Level.SEVERE, "Quest following failed", exception);
                }
            }
            for (EscortStage stage : List.copyOf(stages)) {
                try { stage.tick(); }
                catch (RuntimeException exception) {
                    stage.stopAll();
                    getLogger().log(java.util.logging.Level.SEVERE, "Escort failed: " + stage.profileName, exception);
                }
            }
        }, 1L, 10L);
        getLogger().info("Registered BeautyQuests lumi-follow stage and objective option. Configure following in quest YAML; escort routes in config.yml.");
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    void registerFollowOption() {
        QuestsAPI.getAPI().getStages().autoRegisterOption(new StageOptionAutoRegister() {
            @Override public boolean appliesTo(StageType<?> type) { return true; }
            @Override public <T extends AbstractStage> StageOptionCreator<T> createOptionCreator(StageType<T> type) {
                return StageOptionCreator.create("lumi-follow", FollowStageOption.class,
                        () -> new FollowStageOption<>(type.getStageClass(), LumiFollowPlugin.this));
            }
        });
    }
    List<String> availableProfiles() {
        ConfigurationSection section = getConfig().getConfigurationSection("escorts");
        if (section == null) return List.of("default");
        List<String> keys = new ArrayList<>(section.getKeys(false));
        Collections.sort(keys);
        return keys.isEmpty() ? List.of("default") : keys;
    }
    int reloadPluginConfig() {
        reloadConfig();
        ensureConfiguredNpcs();
        ConfigurationSection section = getConfig().getConfigurationSection("escorts");
        int validCount = 0;
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection sub = section.getConfigurationSection(key);
                if (sub != null && sub.getInt("npc-id", -1) >= 0) {
                    EscortProfile.read(sub);
                    validCount++;
                }
            }
        }
        for (EscortStage stage : List.copyOf(stages)) {
            stage.reloadProfile();
        }
        return validCount;
    }
    @EventHandler public void onCitizensEnable(CitizensEnableEvent event) {
        ensureConfiguredNpcs();
    }
    @EventHandler public void onCitizensReload(CitizensReloadEvent event) {
        ensureConfiguredNpcs();
    }
    void ensureConfiguredNpcs() {
        try {
            if (!CitizensAPI.hasImplementation()) return;
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            if (registry == null) return;
            ConfigurationSection escorts = getConfig().getConfigurationSection("escorts");
            if (escorts == null) return;
            boolean createdAny = false;
            for (String key : escorts.getKeys(false)) {
                ConfigurationSection sub = escorts.getConfigurationSection(key);
                if (sub == null) continue;
                int npcId = sub.getInt("npc-id", -1);
                if (npcId < 0) continue;
                String npcName = sub.getString("npc-name", "").trim();
                Location spawnLoc = readSpawnLocation(sub);
                NPC npc = registry.getById(npcId);
                if (npc == null && !npcName.isEmpty() && spawnLoc != null) {
                    UUID uuid = UUID.nameUUIDFromBytes(("lumifollow-npc-" + npcId).getBytes(StandardCharsets.UTF_8));
                    npc = registry.createNPC(EntityType.PLAYER, uuid, npcId, npcName);
                    npc.setProtected(true);
                    npc.data().setPersistent("collidable", false);
                    npc.spawn(spawnLoc);
                    createdAny = true;
                } else if (npc != null && !npc.isSpawned() && spawnLoc != null) {
                    npc.spawn(spawnLoc);
                }
            }
            if (createdAny) {
                registry.saveToStore();
            }
        } catch (Throwable ignored) {}
    }
    private static Location readSpawnLocation(ConfigurationSection sub) {
        ConfigurationSection locSec = sub.getConfigurationSection("home");
        if (locSec == null) {
            locSec = sub.getConfigurationSection("destination");
        }
        if (locSec == null) return null;
        String worldName = locSec.getString("world", sub.getString("destination.world", "world"));
        if (worldName == null || worldName.isBlank()) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        if (!(locSec.get("x") instanceof Number) || !(locSec.get("y") instanceof Number) || !(locSec.get("z") instanceof Number)) {
            return null;
        }
        return new Location(world, locSec.getDouble("x"), locSec.getDouble("y"), locSec.getDouble("z"));
    }
    boolean releaseNpcLease(int npcId) {
        for (NPC npc : List.copyOf(leases.snapshot().keySet())) {
            if (npc.getId() == npcId) {
                leases.forceRelease(npc);
                if (following != null) following.release(npc);
                return true;
            }
        }
        return false;
    }
    int releaseAllLeases() {
        Set<NPC> leased = new HashSet<>(leases.snapshot().keySet());
        leases.clear();
        if (following != null) {
            for (NPC npc : leased) following.release(npc);
        }
        return leased.size();
    }
    String npcBusyMessage() {
        return getConfig().getString("messages.npc-busy", EscortStage.DEFAULT_BUSY_MESSAGE);
    }
    String npcDiedMessage() {
        return getConfig().getString("messages.npc-died", EscortStage.DEFAULT_DIED_MESSAGE);
    }
    String formatStageDescription(EscortProfile profile) {
        String template = getConfig().getString("messages.stage-description",
                "Escort {npc} to {world} ({x}, {y}, {z})");
        if (template == null) return null;
        String npcName = "NPC " + profile.npcId();
        try {
            if (CitizensAPI.hasImplementation()) {
                NPC npc = CitizensAPI.getNPCRegistry().getById(profile.npcId());
                if (npc != null && npc.getName() != null && !npc.getName().isBlank()) {
                    npcName = npc.getName();
                }
            }
        } catch (Throwable ignored) {}
        return template
                .replace("{npc}", npcName)
                .replace("{npc_name}", npcName)
                .replace("{npc_id}", String.valueOf(profile.npcId()))
                .replace("{world}", profile.world())
                .replace("{x}", String.valueOf(profile.x()))
                .replace("{y}", String.valueOf(profile.y()))
                .replace("{z}", String.valueOf(profile.z()));
    }
    EscortProfile profile(String name) {
        ConfigurationSection section = getConfig().getConfigurationSection("escorts." + name);
        if (section == null) {
            reloadConfig();
            section = getConfig().getConfigurationSection("escorts." + name);
        }
        ensureConfiguredNpcs();
        return EscortProfile.read(section);
    }
    @Override public void onDisable() {
        if (task != null) task.cancel();
        for (EscortStage stage : List.copyOf(stages)) stage.stopAll();
        stages.clear();
        for (FollowStageOption<?> option : List.copyOf(followOptions)) option.stopAll();
        followOptions.clear();
        leases.clear();
        if (following != null) following.close();
    }
}
