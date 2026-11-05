package io.github.saboooor.lumifollow;

import net.citizensnpcs.api.ai.*;
import net.citizensnpcs.api.npc.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowControllerTest {
    private Plugin plugin;
    private NPC npc;
    private Player body, target;
    private World world;
    private Navigator navigator;
    private NavigatorParameters parameters;
    private BukkitScheduler scheduler;
    private BukkitTask task;
    private FollowController controller;

    @BeforeEach void setup() {
        plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenReturn(task);
        npc = mock(NPC.class);
        when(npc.data()).thenReturn(mock(MetadataStore.class));
        navigator = mock(Navigator.class);
        when(npc.getNavigator()).thenReturn(navigator);
        parameters = mock(NavigatorParameters.class, RETURNS_SELF);
        when(navigator.getDefaultParameters()).thenReturn(parameters);
        when(navigator.getLocalParameters()).thenReturn(parameters);
        body = mock(Player.class);
        target = mock(Player.class);
        world = mock(World.class);
        when(npc.getEntity()).thenReturn(body);
        when(npc.isSpawned()).thenReturn(true);
        when(body.isValid()).thenReturn(true);
        when(target.isOnline()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(body.getWorld()).thenReturn(world);
        when(target.getWorld()).thenReturn(world);
        when(body.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(target.getLocation()).thenReturn(new Location(world, 10, 64, 0));
        controller = new FollowController(plugin, npc, FollowOptions.defaults());
    }
    @Test void repeatedFollowingDoesNotCreateDuplicateTasksAndStopCanResume() {
        assertTrue(controller.follow(target));
        assertTrue(controller.follow(target));
        verify(scheduler, times(1)).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L));
        controller.stop();
        verify(task).cancel();
        assertFalse(controller.isFollowing());
        assertTrue(controller.follow(target));
        verify(scheduler, times(2)).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L));
    }
    @Test void reachingPlayerPausesMovementButKeepsFollowingWhenTheyLeave() {
        controller = new FollowController(plugin, npc, FollowOptions.defaults().withClimbing(false, 6, 100));
        controller.follow(target);
        when(target.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        controller.tick();
        assertTrue(controller.isFollowing());
        verify(task, never()).cancel();
        clearInvocations(navigator);
        when(target.getLocation()).thenReturn(new Location(world, 10, 64, 0));
        when(navigator.isNavigating()).thenReturn(false);
        // No ground assist needed for this test; avoid reading terrain through mocks.
        when(body.isOnGround()).thenReturn(false);
        for (int i = 0; i < 10; i++) controller.tick();
        verify(navigator).setTarget(target, false);
    }
    @Test void retargetingCancelsPreviousTaskAndSwitchesPlayer() {
        controller.follow(target);
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.isOnline()).thenReturn(true);
        when(other.getLocation()).thenReturn(new Location(world, 20, 64, 0));
        assertTrue(controller.follow(other));
        assertSame(other, controller.target());
        verify(task).cancel();
        verify(navigator).setTarget(other, false);
    }
    @Test void closeIsIdempotentAndCannotBeRestarted() {
        controller.follow(target);
        controller.close(); controller.close();
        verify(task).cancel();
        assertFalse(controller.follow(target));
        assertFalse(controller.isFollowing());
    }
    @Test void unavailableTargetStopsTask() {
        controller.follow(target);
        when(target.isOnline()).thenReturn(false);
        controller.tick();
        verify(task).cancel();
        assertFalse(controller.isFollowing());
    }
    @Test void disablingRecoveryNeverTeleportsOrSearchesForLandings() {
        controller = new FollowController(plugin, npc, FollowOptions.defaults().withClimbing(false, 6, 100).withRecovery(false, false, false));
        controller.follow(target);
        when(target.getLocation()).thenReturn(new Location(world, 500, 64, 0));
        when(body.isOnGround()).thenReturn(false);
        try (MockedStatic<SafeLocations> safe = mockStatic(SafeLocations.class)) {
            for (int i = 0; i < 60; i++) controller.tick();
            assertFalse(controller.teleportCloser(target));
            safe.verifyNoInteractions();
        }
        verify(npc, never()).teleport(any(), any());
    }
    @Test void unsafeRecoveryDoesNotFallBackToUncheckedPlayerLocation() {
        try (MockedStatic<SafeLocations> safe = mockStatic(SafeLocations.class)) {
            assertFalse(controller.teleportCloser(target));
            verify(npc, never()).teleport(any(), any());
        }
    }
    @Test void disallowedWorldChangeStopsFollowing() {
        controller = new FollowController(plugin, npc, FollowOptions.defaults().withRecovery(true, true, false));
        controller.follow(target);
        World other = mock(World.class);
        when(target.getLocation()).thenReturn(new Location(other, 10, 64, 0));
        controller.tick();
        assertFalse(controller.isFollowing());
        verify(npc, never()).teleport(any(), any());
    }
    @Test void sprintingRequiresTargetToActuallyMoveAway() {
        Location pursuer = new Location(world, 0, 64, 0), previous = new Location(world, 5, 64, 0);
        assertTrue(FollowController.runningAway(pursuer, previous, new Location(world, 5.3, 64, 0), true));
        assertFalse(FollowController.runningAway(pursuer, previous, new Location(world, 4.7, 64, 0), true));
        assertFalse(FollowController.runningAway(pursuer, previous, previous, true));
        assertFalse(FollowController.runningAway(pursuer, previous, new Location(world, 5.3, 64, 0), false));
    }
    @Test void serviceReturnsOneControllerAndReleaseDisposesIt() {
        LumiFollow service = new LumiFollow(plugin);
        FollowController first = service.controller(npc, FollowOptions.defaults());
        assertSame(first, service.controller(npc, FollowOptions.defaults()));
        first.follow(target);
        service.release(npc);
        verify(task).cancel();
        assertFalse(first.follow(target));
        assertNotSame(first, service.controller(npc, FollowOptions.defaults()));
        service.close();
        assertThrows(IllegalStateException.class, () -> service.controller(npc, FollowOptions.defaults()));
    }
    @Test void teleportEventsAreDeferredAndCoalescedAndStoppedSessionsStayStopped() {
        LumiFollow service = new LumiFollow(plugin);
        FollowController session = service.follow(npc, target, FollowOptions.defaults());
        org.bukkit.event.player.PlayerTeleportEvent teleport = mock(org.bukkit.event.player.PlayerTeleportEvent.class);
        when(teleport.getPlayer()).thenReturn(target);
        Location destination = target.getLocation();
        when(teleport.getTo()).thenReturn(destination);
        org.bukkit.event.player.PlayerChangedWorldEvent worldChange = mock(org.bukkit.event.player.PlayerChangedWorldEvent.class);
        when(worldChange.getPlayer()).thenReturn(target);
        service.onTeleport(teleport);
        service.onWorldChange(worldChange);
        org.mockito.ArgumentCaptor<Runnable> pending = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1)).runTask(eq(plugin), pending.capture());
        session.stop();
        try (MockedStatic<SafeLocations> safe = mockStatic(SafeLocations.class)) {
            pending.getValue().run();
            safe.verifyNoInteractions();
        }
        service.close();
    }
    @Test void successfulRecoveryResetsMotionAndResumesNavigation() {
        controller.follow(target);
        Location landing = new Location(world, 6, 64, 0);
        doAnswer(call -> { when(body.getLocation()).thenReturn(landing); return null; })
                .when(npc).teleport(eq(landing), any());
        try (MockedStatic<SafeLocations> safe = mockStatic(SafeLocations.class)) {
            safe.when(() -> SafeLocations.findNear(target.getLocation(), 4, 8)).thenReturn(landing);
            assertTrue(controller.teleportCloser(target));
        }
        verify(body).setVelocity(new org.bukkit.util.Vector());
        verify(navigator, times(2)).setTarget(target, false);
    }
    @Test void cancelledNpcTeleportReportsFailure() {
        Location landing = new Location(world, 6, 64, 0);
        // A cancelled NPC teleport leaves its location unchanged.
        assertFalse(controller.teleport(landing));
        verify(body, never()).setVelocity(any());
    }
    @Test void targetDeathAndOwnerDisableCleanUpSessionsWithoutDestroyingNpc() {
        LumiFollow service = new LumiFollow(plugin);
        FollowController session = service.follow(npc, target, FollowOptions.defaults());
        org.bukkit.event.entity.PlayerDeathEvent death = mock(org.bukkit.event.entity.PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(target);
        service.onDeath(death);
        assertFalse(session.isFollowing());
        session.follow(target);
        org.bukkit.event.server.PluginDisableEvent disable = mock(org.bukkit.event.server.PluginDisableEvent.class);
        when(disable.getPlugin()).thenReturn(plugin);
        service.onDisable(disable);
        assertFalse(session.isFollowing());
        assertFalse(session.follow(target));
        verify(npc, never()).destroy();
        assertThrows(IllegalStateException.class, () -> service.controller(npc, FollowOptions.defaults()));
    }
}
