package io.github.saboooor.lumifollow.quests;

import io.github.saboooor.lumifollow.FollowOptions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import java.util.Locale;

/** Validated configuration independent of whether the destination world is currently loaded. */
record EscortProfile(int npcId, String world, double x, double y, double z,
                     double radius, FollowOptions options, EndPolicy endPolicy,
                     HomeLocation home, boolean cloneNpc, boolean failOnDeath,
                     int failureBranch) {
    enum EndPolicy { STAY, RETURN_TO_START, RETURN_HOME }

    record HomeLocation(String world, double x, double y, double z) {
        HomeLocation {
            if (world == null || world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Escort home requires world, x, y and z");
        }
        Location toBukkitLocation() {
            World bukkitWorld = Bukkit.getWorld(world);
            return bukkitWorld != null ? new Location(bukkitWorld, x, y, z) : null;
        }
    }

    EscortProfile {
        if (npcId < 0 || world == null || world.isBlank() || !Double.isFinite(x)
                || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(radius) || radius <= 0
                || options == null || radius < options.stopDistance() || endPolicy == null)
            throw new IllegalArgumentException("Escort needs npc-id >= 0, a destination, and arrival-radius >= stop distance");
    }

    EscortProfile(int npcId, String world, double x, double y, double z,
                  double radius, FollowOptions options) {
        this(npcId, world, x, y, z, radius, options, EndPolicy.STAY, null, false, false, -1);
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
        FollowOptions options = FollowOptions.read(section, false, false);
        String policyRaw = section.getString("on-end", "STAY");
        EndPolicy endPolicy;
        try {
            endPolicy = EndPolicy.valueOf(policyRaw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid escort on-end policy: " + policyRaw);
        }
        HomeLocation home = null;
        if (section.isConfigurationSection("home")) {
            for (String coordinate : new String[]{"x", "y", "z"}) {
                if (!(section.get("home." + coordinate) instanceof Number))
                    throw new IllegalArgumentException("Escort home coordinates must be numbers");
            }
            String homeWorld = section.getString("home.world", section.getString("destination.world"));
            home = new HomeLocation(homeWorld, section.getDouble("home.x"),
                    section.getDouble("home.y"), section.getDouble("home.z"));
        }
        boolean cloneNpc = section.getBoolean("clone-npc", false);
        int failureBranch = section.getInt("death-branch", -1);
        boolean failOnDeath = section.getBoolean("fail-on-npc-death", failureBranch >= 0);
        return new EscortProfile(section.getInt("npc-id", -1), section.getString("destination.world"),
                section.getDouble("destination.x"), section.getDouble("destination.y"),
                section.getDouble("destination.z"), section.getDouble("arrival-radius", 4),
                options, endPolicy, home, cloneNpc, failOnDeath, failureBranch);
    }

    boolean arrived(Location location) {
        if (location == null || location.getWorld() == null || !world.equals(location.getWorld().getName())) return false;
        double dx = location.getX() - x, dy = location.getY() - y, dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
