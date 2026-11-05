package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.stages.*;
import fr.skytasul.quests.api.stages.options.*;
import fr.skytasul.quests.api.stages.creation.StageCreation;
import io.github.saboooor.lumifollow.LumiFollow;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

public final class LumiFollowPlugin extends JavaPlugin {
    final Set<EscortStage> stages = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<FollowStageOption<?>> followOptions = Collections.newSetFromMap(new IdentityHashMap<>());
    final NpcLeases leases = new NpcLeases();
    LumiFollow following;
    private BukkitTask task;

    @Override public void onEnable() {
        saveDefaultConfig();
        following = new LumiFollow(this);
        registerFollowOption();
        QuestsAPI.getAPI().getStages().register(new StageType<>("lumi-follow", EscortStage.class,
                "LumiFollow escort", (section, controller) -> new EscortStage(controller, this,
                        section.getString("profile", "default")), new ItemStack(Material.LEAD),
                context -> new StageCreation<EscortStage>(context) {
                    private String profile = "default";
                    @Override public void edit(EscortStage stage) { super.edit(stage); profile = stage.profileName; }
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
    EscortProfile profile(String name) {
        return EscortProfile.read(getConfig().getConfigurationSection("escorts." + name));
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
