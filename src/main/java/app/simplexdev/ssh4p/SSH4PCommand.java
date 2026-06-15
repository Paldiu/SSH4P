package app.simplexdev.ssh4p;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import app.simplexdev.ssh4p.ssh.SshPipelineBootstrap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collections;
import java.util.List;

public class SSH4PCommand implements TabExecutor {
    private final SSH4P plugin;

    public SSH4PCommand(SSH4P plugin) {
        this.plugin = plugin;
        plugin.getCommand("ssh4p").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("SSH4P — SSH console plugin for Bukkit servers.")
                                        .color(NamedTextColor.GREEN));
            sender.sendMessage(Component.text("Usage: /ssh4p <reload|debug|purge>")
                                        .color(NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("SSH4P configuration reloaded.")
                                            .color(NamedTextColor.GREEN));
                return true;
            }
            case "debug" -> {
                SSHLogger.get().debug("Debug command executed by " + sender.getName());
                sender.sendMessage(Component.text("Debug message logged. Check console for details.")
                                            .color(NamedTextColor.GREEN));
                return true;
            }
            case "purge" -> {
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage(Component.text("Only the console can execute the purge command.")
                                                .color(NamedTextColor.RED));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /ssh4p purge <username|all>")
                                                .color(NamedTextColor.YELLOW));
                    return true;
                }

                SshPipelineBootstrap bootstrap = plugin.getSshPipelineBootstrap();
                if (args[1].equalsIgnoreCase("all")) {
                    int count = bootstrap.purgeAll();
                    sender.sendMessage(Component.text("Purged " + count + " active SSH session(s).")
                                                .color(NamedTextColor.GREEN));
                } else {
                    int count = bootstrap.purgeUser(args[1]);
                    if (count == 0) {
                        sender.sendMessage(Component.text("No active SSH sessions found for user: " + args[1])
                                                    .color(NamedTextColor.YELLOW));
                    } else {
                        sender.sendMessage(Component.text("Purged " + count + " SSH session(s) for user: " + args[1])
                                                    .color(NamedTextColor.GREEN));
                    }
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "debug", "purge").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
            return List.of("all").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .toList();
        }
        return Collections.emptyList();
    }
}