package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.players.PlayerQuester;
import fr.skytasul.quests.api.stages.*;
import io.github.saboooor.lumifollow.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowStageOptionTest {
    MockedStatic<QuestsAPI> apiMock;
    LumiFollowPlugin plugin;
    StageTypeRegistry types;
    StageController controller;
    PlayerQuester quester;
    Player player;
    NPC npc;
    FollowStageOption<TestStage> option;
    UUID entityId = UUID.randomUUID();

    static class TestStage extends AbstractStage {
        TestStage(StageController controller) { super(controller); }
        @Override public String getDefaultDescription(StageDescriptionPlaceholdersContext context) { return "Test"; }
    }
    @BeforeEach void setup() throws Exception {
        QuestsAPI api = mock(QuestsAPI.class);
        apiMock = mockStatic(QuestsAPI.class);
        apiMock.when(QuestsAPI::getAPI).thenReturn(api);
        types = new StageTypeRegistry();
        when(api.getStages()).thenReturn(types);
        plugin = mock(LumiFollowPlugin.class);
        for (String name : List.of("followOptions", "stages")) {
            var field = LumiFollowPlugin.class.getDeclaredField(name);
            field.setAccessible(true); field.set(plugin, new HashSet<>());
        }
        var field = LumiFollowPlugin.class.getDeclaredField("leases");
        field.setAccessible(true); field.set(plugin, new NpcLeases());
        plugin.following = mock(LumiFollow.class);
        when(plugin.isEnabled()).thenReturn(true);
        doCallRealMethod().when(plugin).registerFollowOption();
        plugin.registerFollowOption(); // Register before the consuming plugin's custom stage exists.
        var type = new StageType<TestStage>("test", TestStage.class, "Test",
                (section, ctrl) -> new TestStage(ctrl), mock(ItemStack.class), context -> null);
        types.register(type);
        controller = mock(StageController.class);
        doReturn(type).when(controller).getStageType();
        var stage = new TestStage(controller);
        when(controller.getStage()).thenReturn(stage);
        option = (FollowStageOption<TestStage>) stage.getOptions().stream()
                .filter(value -> value instanceof FollowStageOption).findFirst().orElseThrow();
        quester = mock(PlayerQuester.class);
        player = mock(Player.class);
        when(quester.getPlayer()).thenReturn(Optional.of(player));
        when(player.isOnline()).thenReturn(true);
        when(controller.hasStarted(quester)).thenReturn(true);
        when(controller.getData(quester, "npc", String.class)).thenReturn(entityId.toString());
        npc = mock(NPC.class);
        when(npc.isSpawned()).thenReturn(true);
        option.stageLoad(controller);
    }
    @AfterEach void cleanup() { option.stopAll(); apiMock.close(); }

    private void enable() {
        var yaml = new YamlConfiguration();
        yaml.set("enabled", true);
        yaml.set("open-doors", false);
        option.load(yaml);
    }
    @Test void absentOptionDoesNotFollowAnyNpc() {
        assertFalse(option.shouldSave());
        option.stageStart(quester, controller);
        option.tick();
        verifyNoInteractions(plugin.following);
    }
    @Test void questOptionFollowsTemporaryNpcAndEndsWithoutCompletingObjective() {
        enable();
        var registry = mock(NPCRegistry.class);
        when(registry.getNPC(player)).thenReturn(npc);
        try (var bukkit = mockStatic(Bukkit.class); var citizens = mockStatic(CitizensAPI.class)) {
            bukkit.when(() -> Bukkit.getEntity(entityId)).thenReturn(player);
            citizens.when(CitizensAPI::getNPCRegistries).thenReturn(List.of(registry));
            option.stageStart(quester, controller);
            option.stageUpdated(quester, controller);
            verify(plugin.following).follow(npc, player, FollowOptions.defaults().withDoors(false).withRecovery(true, true, false));
            option.stageEnd(quester, controller);
            verify(plugin.following).release(npc);
            option.tick();
            verifyNoMoreInteractions(plugin.following);
            verify(controller, never()).finishStage(any());
        }
    }
    @Test void missingOrMalformedNpcDataWaitsWithoutFollowing() {
        enable();
        when(controller.getData(quester, "npc", String.class)).thenReturn(null, "not-a-uuid");
        option.stageStart(quester, controller);
        option.tick();
        option.tick();
        verifyNoInteractions(plugin.following);
    }
    @Test void disablingPluginReleasesPursuitAndBlocksFurtherQuestUpdates() {
        enable();
        var registry = mock(NPCRegistry.class);
        when(registry.getNPC(player)).thenReturn(npc);
        try (var bukkit = mockStatic(Bukkit.class); var citizens = mockStatic(CitizensAPI.class)) {
            bukkit.when(() -> Bukkit.getEntity(entityId)).thenReturn(player);
            citizens.when(CitizensAPI::getNPCRegistries).thenReturn(List.of(registry));
            option.stageStart(quester, controller);
            option.tick();
            when(plugin.isEnabled()).thenReturn(false);
            option.tick();
            verify(plugin.following).release(npc);
            clearInvocations(plugin.following);
            option.stageUpdated(quester, controller);
            option.stageStart(quester, controller);
            option.tick();
            verifyNoInteractions(plugin.following);
        }
    }

    @Test void twoQuestersCannotStealTheSameNpc() {
        enable();
        var registry = mock(NPCRegistry.class);
        when(registry.getNPC(player)).thenReturn(npc);
        PlayerQuester other = mock(PlayerQuester.class);
        when(other.getPlayer()).thenReturn(Optional.of(player));
        when(controller.hasStarted(other)).thenReturn(true);
        when(controller.getData(other, "npc", String.class)).thenReturn(entityId.toString());
        try (var bukkit = mockStatic(Bukkit.class); var citizens = mockStatic(CitizensAPI.class)) {
            bukkit.when(() -> Bukkit.getEntity(entityId)).thenReturn(player);
            citizens.when(CitizensAPI::getNPCRegistries).thenReturn(List.of(registry));
            option.stageUpdated(quester, controller);
            option.stageUpdated(other, controller);
            verify(plugin.following, times(1)).follow(eq(npc), eq(player), any());
            option.stageEnd(other, controller);
            verify(plugin.following, never()).release(npc);
            option.stageEnd(quester, controller);
            verify(plugin.following).release(npc);
        }
    }

    @Test void unloadingObjectiveStopsFollowingAndUnregistersIt() {
        enable();
        var registry = mock(NPCRegistry.class);
        when(registry.getNPC(player)).thenReturn(npc);
        try (var bukkit = mockStatic(Bukkit.class); var citizens = mockStatic(CitizensAPI.class)) {
            bukkit.when(() -> Bukkit.getEntity(entityId)).thenReturn(player);
            citizens.when(CitizensAPI::getNPCRegistries).thenReturn(List.of(registry));
            option.stageUpdated(quester, controller);
            option.stageUnload(controller);
            verify(plugin.following).release(npc);
            assertFalse(plugin.followOptions.contains(option));
            assertTrue(option.getStageController().isEmpty());
        }
    }

    @Test void serializationPreservesQuestMovementSettings() {
        enable();
        var yaml = new YamlConfiguration();
        option.save(yaml);
        assertTrue(yaml.getBoolean("enabled"));
        assertFalse(yaml.getBoolean("open-doors"));
        assertEquals("npc", yaml.getString("npc-data"));
        var copy = option.clone();
        var saved = new YamlConfiguration();
        copy.save(saved);
        assertEquals(yaml.saveToString(), saved.saveToString());
    }
}
