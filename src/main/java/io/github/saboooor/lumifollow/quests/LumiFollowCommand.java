package io.github.saboooor.lumifollow.quests;

import io.github.saboooor.lumifollow.FollowController;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Admin command for reloading escort profiles, inspecting active sessions/leases, and releasing stuck NPCs. */
public final class LumiFollowCommand implements CommandExecutor, TabCompleter {
    private final LumiFollowPlugin plugin;

    public LumiFollowCommand(LumiFollowPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lumifollow.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "release" -> handleRelease(sender, args);
            default -> {
                sender.sendMessage("§eUsage: /" + label + " <status|reload|release <npc-id|all>>");
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        try {
            int count = plugin.reloadPluginConfig();
            sender.sendMessage("§aLumiFollow configuration reloaded (" + count + " escort profile(s) loaded).");
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cFailed to reload LumiFollow configuration: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        int activeEscortSessions = plugin.stages.stream().mapToInt(EscortStage::activeSessionCount).sum();
        int activeOptionSessions = plugin.followOptions.stream().mapToInt(FollowStageOption::activeSessionCount).sum();
        Map<NPC, Object> leased = plugin.leases.snapshot();
        Map<NPC, FollowController> controllers = plugin.following != null ? plugin.following.controllers() : Map.of();

        sender.sendMessage("§6--- LumiFollow Status ---");
        sender.sendMessage("§7Escort stages loaded: §f" + plugin.stages.size()
                + " §7(active sessions: §f" + activeEscortSessions + "§7)");
        sender.sendMessage("§7Follow stage options loaded: §f" + plugin.followOptions.size()
                + " §7(active sessions: §f" + activeOptionSessions + "§7)");
        sender.sendMessage("§7Active NPC leases: §f" + leased.size());
        for (NPC npc : leased.keySet()) {
            FollowController controller = controllers.get(npc);
            Player target = controller != null ? controller.target() : null;
            String targetName = target != null ? target.getName() : "none";
            sender.sendMessage(" §8- §eNPC #" + npc.getId() + " (" + npc.getName() + ") §7-> following: §f" + targetName);
        }
        return true;
    }

    private boolean handleRelease(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /lumifollow release <npc-id|all>");
            return true;
        }
        if ("all".equalsIgnoreCase(args[1])) {
            int count = plugin.releaseAllLeases();
            sender.sendMessage("§aReleased " + count + " NPC lease(s).");
            return true;
        }
        int npcId;
        try {
            npcId = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid NPC ID: " + args[1]);
            return true;
        }
        if (plugin.releaseNpcLease(npcId)) {
            sender.sendMessage("§aReleased lease and stopped following for NPC #" + npcId + ".");
        } else {
            sender.sendMessage("§eNPC #" + npcId + " is not currently leased by LumiFollow.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("lumifollow.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "reload", "release").stream()
                    .filter(opt -> opt.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && "release".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            suggestions.add("all");
            for (NPC npc : plugin.leases.snapshot().keySet()) {
                suggestions.add(String.valueOf(npc.getId()));
            }
            return suggestions.stream().filter(opt -> opt.startsWith(prefix)).toList();
        }
        return List.of();
    }
}

