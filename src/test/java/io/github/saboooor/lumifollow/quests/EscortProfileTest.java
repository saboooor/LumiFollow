package io.github.saboooor.lumifollow.quests;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EscortProfileTest {
    YamlConfiguration config() {
        var yaml = new YamlConfiguration();
        yaml.set("npc-id", 7);
        yaml.set("destination.world", "world");
        yaml.set("destination.x", 10);
        yaml.set("destination.y", 64);
        yaml.set("destination.z", 20);
        return yaml;
    }
    @Test void destinationRequiresBothDistanceAndWorldAndOptionsAreRespected() {
        var yaml = config();
        yaml.set("climbing", false);
        yaml.set("teleport-recovery", false);
        var profile = EscortProfile.read(yaml);
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        assertTrue(profile.arrived(new Location(world, 10, 64, 20)));
        assertFalse(profile.arrived(new Location(world, 15, 64, 20)));
        when(world.getName()).thenReturn("other");
        assertFalse(profile.arrived(new Location(world, 10, 64, 20)));
        assertFalse(profile.options().climbing());
        assertFalse(profile.options().teleportRecovery());
        assertFalse(profile.options().openDoors());
        assertFalse(profile.options().crossWorld());
    }
    @Test void invalidOrUnconfiguredRoutesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EscortProfile.read(null));
        for (var invalid : new Object[][]{{"npc-id", -1}, {"destination.x", Double.NaN},
                {"arrival-radius", 1}, {"destination.world", ""}, {"destination.z", "not a number"},
                {"on-end", "INVALID_POLICY"}, {"home.x", "bad"}}) {
            var yaml = config();
            yaml.set((String) invalid[0], invalid[1]);
            assertThrows(IllegalArgumentException.class, () -> EscortProfile.read(yaml));
        }
    }
    @Test void readsExtendedLifecycleAndMovementOptions() {
        var yaml = config();
        yaml.set("on-end", "return-home");
        yaml.set("home.world", "world");
        yaml.set("home.x", 1);
        yaml.set("home.y", 65);
        yaml.set("home.z", 2);
        yaml.set("clone-npc", true);
        yaml.set("fail-on-npc-death", true);
        yaml.set("death-branch", 3);
        yaml.set("walk-speed", 1.3);
        yaml.set("close-doors-delay-ticks", 40);
        yaml.set("mount-vehicles", true);
        var profile = EscortProfile.read(yaml);
        assertEquals(EscortProfile.EndPolicy.RETURN_HOME, profile.endPolicy());
        assertNotNull(profile.home());
        assertEquals(1.0, profile.home().x());
        assertTrue(profile.cloneNpc());
        assertTrue(profile.failOnDeath());
        assertEquals(3, profile.failureBranch());
        assertEquals(1.3F, profile.options().walkSpeed(), 0.001F);
        assertEquals(40, profile.options().closeDoorsDelayTicks());
        assertTrue(profile.options().mountVehicles());
    }
}
