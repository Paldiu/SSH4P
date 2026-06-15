package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.api.MainThreadCommandBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;

/**
 * Bukkit implementation of the main-thread command dispatch bridge.
 * <p>
 * Directly integrates with Bukkit's command API to execute commands via the CommandMap.
 * This approach bypasses command dispatching overhead and provides direct access to command objects.
 */
public final class BukkitMainThreadCommandBridge implements MainThreadCommandBridge {

    /**
     * Dispatches a command as the server console sender via direct command API integration.
     * <p>
     * Parses the command line, looks up the command in the CommandMap, and executes it
     * directly with the console sender. Falls back to legacy dispatch if the command is not found.
     *
     * @param commandLine the command to execute (without leading slash)
     */
    @Override
    public void dispatchAsConsole(String commandLine) {
        String[] parts = commandLine.split(" ", 2);
        String label = parts[0];
        String[] args = parts.length > 1 ? parts[1].split(" ") : new String[0];

        Command command = Bukkit.getServer().getCommandMap().getCommand(label);
        if (command != null) {
            try {
                command.execute(Bukkit.getConsoleSender(), label, args);
            } catch (Exception e) {
                SSHLogger.get().warn("Error executing command '" + label + "': " + e.getMessage(), e);
            }
        } else {
            SSHLogger.get().warn("Unknown command: " + label);
        }
    }
}
