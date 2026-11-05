package io.github.saboooor.lumifollow.quests;

import io.github.saboooor.lumifollow.FollowOptions;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/** Validated configuration independent of whether the destination world is currently loaded. */
record EscortProfile(int npcId, String world, double x, double y, double z,
                     double radius, FollowOptions options) {
    EscortProfile {
        if (npcId < 0 || world == null || world.isBlank() || !Double.isFinite(x)
                || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(radius) || radius <= 0
                || radius < options.stopDistance())
            throw new IllegalArgumentException("Escort needs npc-id >= 0, a destination, and arrival-radius >= stop distance");
    }
    static EscortProfile read(ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Unknown LumiFollow escort profile");
        if (!section.isConfigurationSection("destination") || !section.isSet("destination.x")
                || !section.isSet("destination.y") || !section.isSet("destination.z"))
            throw new IllegalArgumentException("Escort destination requires world, x, y and z");
        for (String coordinate : new String[]{"x", "y", "z"}) {
            if (!(section.get("destination." + coordinate) instanceof Number))
                throw new IllegalArgumentException("Escort destination coordinates must be numbers");
        }
        var options = FollowOptions.defaults().withDoors(section.getBoolean("open-doors", false))
                .withClimbing(section.getBoolean("climbing", true), section.getInt("max-climb-height", 6),
                        section.getInt("climb-timeout-ticks", 100))
                .withRecovery(section.getBoolean("teleport-recovery", true),
                        section.getBoolean("follow-teleports", true), section.getBoolean("cross-world", false));
        return new EscortProfile(section.getInt("npc-id", -1), section.getString("destination.world"),
                section.getDouble("destination.x"), section.getDouble("destination.y"),
                section.getDouble("destination.z"), section.getDouble("arrival-radius", 4), options);
    }
    boolean arrived(Location location) {
        if (location.getWorld() == null || !world.equals(location.getWorld().getName())) return false;
        double dx = location.getX() - x, dy = location.getY() - y, dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
