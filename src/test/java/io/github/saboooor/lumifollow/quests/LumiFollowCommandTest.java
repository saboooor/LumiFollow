package io.github.saboooor.lumifollow.quests;

import io.github.saboooor.lumifollow.LumiFollow;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LumiFollowCommandTest {
    private LumiFollowPlugin plugin;
    private LumiFollowCommand commandExecutor;
    private CommandSender sender;
    private Command command;
    private NpcLeases leases;

    @BeforeEach void setup() throws Exception {
        plugin = mock(LumiFollowPlugin.class);
        for (String name : List.of("followOptions", "stages")) {
            var field = LumiFollowPlugin.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(plugin, new HashSet<>());
        }
        var leaseField = LumiFollowPlugin.class.getDeclaredField("leases");
        leaseField.setAccessible(true);
        leases = new NpcLeases();
        leaseField.set(plugin, leases);
        plugin.following = mock(LumiFollow.class);
        when(plugin.releaseNpcLease(anyInt())).thenCallRealMethod();
        when(plugin.releaseAllLeases()).thenCallRealMethod();

        commandExecutor = new LumiFollowCommand(plugin);
        sender = mock(CommandSender.class);
        when(sender.hasPermission("lumifollow.admin")).thenReturn(true);
        command = mock(Command.class);
    }

    @Test void deniesExecutionAndTabCompleteWithoutPermission() {
        when(sender.hasPermission("lumifollow.admin")).thenReturn(false);
        assertTrue(commandExecutor.onCommand(sender, command, "lumifollow", new String[]{"reload"}));
        verify(plugin, never()).reloadPluginConfig();
        assertTrue(commandExecutor.onTabComplete(sender, command, "lumifollow", new String[]{""}).isEmpty());
    }

    @Test void reloadInvokesPluginReload() {
        when(plugin.reloadPluginConfig()).thenReturn(2);
        assertTrue(commandExecutor.onCommand(sender, command, "lumifollow", new String[]{"reload"}));
        verify(plugin).reloadPluginConfig();
        verify(sender).sendMessage(contains("2 escort profile(s)"));
    }

    @Test void releaseFreesSpecificOrAllNpcLeases() {
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(12);
        when(npc.getName()).thenReturn("Guard");
        leases.acquire(npc, new Object());

        assertEquals(List.of("all", "12"),
                commandExecutor.onTabComplete(sender, command, "lumifollow", new String[]{"release", ""}));

        assertTrue(commandExecutor.onCommand(sender, command, "lumifollow", new String[]{"release", "12"}));
        verify(plugin.following).release(npc);
        assertTrue(leases.snapshot().isEmpty());
    }
}

