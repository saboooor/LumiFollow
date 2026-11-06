package io.github.saboooor.lumifollow.quests;

import fr.skytasul.quests.api.players.PlayerQuester;
import fr.skytasul.quests.api.stages.StageController;
import io.github.saboooor.lumifollow.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EscortStageTest {
    LumiFollowPlugin plugin;
    StageController controller;
    PlayerQuester quester;
    Player player;
    NPC npc;
    EscortProfile profile;
    NpcLeases leases;
    @BeforeEach void setup() throws Exception {
        plugin = mock(LumiFollowPlugin.class);
        var stages = LumiFollowPlugin.class.getDeclaredField("stages");
        stages.setAccessible(true); stages.set(plugin, new HashSet<>());
        var field = LumiFollowPlugin.class.getDeclaredField("leases");
        field.setAccessible(true); leases = new NpcLeases(); field.set(plugin, leases);
        plugin.following = mock(LumiFollow.class);
        controller = mock(StageController.class, RETURNS_DEEP_STUBS);
        when(controller.getStageType().getOptionsRegistry().getCreators()).thenReturn(List.of());
        quester = mock(PlayerQuester.class);
        player = mock(Player.class);
        when(quester.getPlayer()).thenReturn(Optional.of(player));
        when(player.isOnline()).thenReturn(true);
        when(controller.hasStarted(quester)).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location playerLocation = mock(Location.class);
        when(playerLocation.getWorld()).thenReturn(world);
        when(playerLocation.getX()).thenReturn(0d);
        when(playerLocation.getY()).thenReturn(64d);
        when(playerLocation.getZ()).thenReturn(0d);
        when(player.getLocation()).thenReturn(playerLocation);
        profile = new EscortProfile(7, "world", 10, 64, 0, 4, FollowOptions.defaults());
        when(plugin.profile("route")).thenReturn(profile);
        npc = mock(NPC.class);
        when(npc.isSpawned()).thenReturn(true);
        Player npcEntity = mock(Player.class);
        Location npcLocation = mock(Location.class);
        when(npcLocation.getWorld()).thenReturn(world);
        when(npcLocation.getX()).thenReturn(10d);
        when(npcLocation.getY()).thenReturn(64d);
        when(npcLocation.getZ()).thenReturn(0d);
        when(npc.getEntity()).thenReturn(npcEntity);
        when(npcEntity.getLocation()).thenReturn(npcLocation);
    }
    @Test void followsOnlyDuringStageAndCancellationReleasesNpc() {
        var registry = mock(NPCRegistry.class);
        when(registry.getById(7)).thenReturn(npc);
        try (var citizens = mockStatic(CitizensAPI.class)) {
            citizens.when(CitizensAPI::getNPCRegistry).thenReturn(registry);
            var stage = new EscortStage(controller, plugin, "route");
            stage.started(quester);
            stage.tick();
            verify(plugin.following).follow(npc, player, profile.options());
            verify(controller, never()).finishStage(any());
            when(controller.hasStarted(quester)).thenReturn(false);
            stage.tick();
            verify(plugin.following).release(npc);
            assertTrue(leases.acquire(npc, new Object()));
        }
    }
    @Test void completesOnlyWhenPlayerAndNpcArriveAndReleasesBeforeAdvancing() {
        var registry = mock(NPCRegistry.class);
        when(registry.getById(7)).thenReturn(npc);
        Location npcLocation = npcEntityLocation();
        when(player.getLocation()).thenReturn(npcLocation);
        try (var citizens = mockStatic(CitizensAPI.class)) {
            citizens.when(CitizensAPI::getNPCRegistry).thenReturn(registry);
            var stage = new EscortStage(controller, plugin, "route");
            stage.started(quester);
            stage.tick();
            var order = inOrder(plugin.following, controller);
            order.verify(plugin.following).release(npc);
            order.verify(controller).finishStage(quester);
            stage.tick();
            verify(controller, times(1)).finishStage(quester);
        }
    }

    private Location npcEntityLocation() {
        return npc.getEntity().getLocation();
    }

    @Test void busyNpcIsNeverStolenAndWaitingPlayerCanRetry() {
        var registry = mock(NPCRegistry.class);
        when(registry.getById(7)).thenReturn(npc);
        Object otherOwner = new Object();
        assertTrue(leases.acquire(npc, otherOwner));
        try (var citizens = mockStatic(CitizensAPI.class)) {
            citizens.when(CitizensAPI::getNPCRegistry).thenReturn(registry);
            var stage = new EscortStage(controller, plugin, "route");
            stage.started(quester);
            stage.tick();
            verifyNoInteractions(plugin.following);
            stage.ended(quester);
            assertFalse(leases.acquire(npc, new Object()));
            assertTrue(leases.release(npc, otherOwner));
            stage.joined(player, quester);
            stage.tick();
            verify(plugin.following).follow(npc, player, profile.options());
            stage.left(player, quester);
            verify(plugin.following).release(npc);
        }
    }

    @Test void missingProfileDoesNotBreakQuestLoadingAndStageWaits() {
        when(plugin.profile("missing")).thenThrow(new IllegalArgumentException("Unknown LumiFollow escort profile 'missing'"));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        var stage = assertDoesNotThrow(() -> new EscortStage(controller, plugin, "missing"));
        assertNull(stage.profile());
        stage.started(quester);
        stage.tick();
        stage.tick();
        verify(player, times(1)).sendMessage(contains("missing"));
        verifyNoInteractions(plugin.following);
        verify(controller, never()).finishStage(any());
    }

    @Test void returnToStartPolicyTeleportsNpcBackToSnapshotOriginOnRelease() {
        var registry = mock(NPCRegistry.class);
        when(registry.getById(7)).thenReturn(npc);
        Location startClone = mock(Location.class);
        World world = npc.getEntity().getLocation().getWorld();
        when(startClone.getWorld()).thenReturn(world);
        when(npc.getEntity().getLocation().clone()).thenReturn(startClone);
        var returnProfile = new EscortProfile(7, "world", 10, 64, 0, 4, FollowOptions.defaults(),
                EscortProfile.EndPolicy.RETURN_TO_START, null, false, false, -1);
        when(plugin.profile("route")).thenReturn(returnProfile);
        try (var citizens = mockStatic(CitizensAPI.class)) {
            citizens.when(CitizensAPI::getNPCRegistry).thenReturn(registry);
            var stage = new EscortStage(controller, plugin, "route");
            stage.started(quester);
            stage.tick();
            stage.ended(quester);
            verify(npc).teleport(eq(startClone), eq(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN));
        }
    }

    @Test void cloneNpcModeSpawnsPerPlayerCloneAndDestroysItOnEnd() {
        var registry = mock(NPCRegistry.class);
        when(registry.getById(7)).thenReturn(npc);
        NPC cloned = mock(NPC.class);
        var entity = npc.getEntity();
        when(npc.clone()).thenReturn(cloned);
        when(cloned.isSpawned()).thenReturn(true);
        when(cloned.getEntity()).thenReturn(entity);
        var cloneProfile = new EscortProfile(7, "world", 10, 64, 0, 4, FollowOptions.defaults(),
                EscortProfile.EndPolicy.STAY, null, true, false, -1);
        when(plugin.profile("route")).thenReturn(cloneProfile);
        try (var citizens = mockStatic(CitizensAPI.class)) {
            citizens.when(CitizensAPI::getNPCRegistry).thenReturn(registry);
            var stage = new EscortStage(controller, plugin, "route");
            stage.started(quester);
            stage.tick();
            verify(plugin.following).follow(cloned, player, cloneProfile.options());
            stage.ended(quester);
            verify(plugin.following).release(cloned);
            verify(cloned).destroy();
        }
    }

    @Test void npcDeathFailsObjectiveAndCancelsOrBranchesQuest() {
        var registry = mock(NPCRegistry.class);
        when(registry.getById(7)).thenReturn(npc);
        var deathProfile = new EscortProfile(7, "world", 10, 64, 0, 4, FollowOptions.defaults(),
                EscortProfile.EndPolicy.STAY, null, false, true, -1);
        when(plugin.profile("route")).thenReturn(deathProfile);
        fr.skytasul.quests.api.quests.Quest quest = mock(fr.skytasul.quests.api.quests.Quest.class);
        when(controller.getBranch().getQuest()).thenReturn(quest);
        try (var citizens = mockStatic(CitizensAPI.class)) {
            citizens.when(CitizensAPI::getNPCRegistry).thenReturn(registry);
            var stage = new EscortStage(controller, plugin, "route");
            stage.started(quester);
            stage.tick();
            when(((Player) npc.getEntity()).isDead()).thenReturn(true);
            stage.tick();
            verify(plugin.following).release(npc);
            verify(quest).cancelQuester(quester);
        }
    }
}
