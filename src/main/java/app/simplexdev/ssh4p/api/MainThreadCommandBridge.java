package app.simplexdev.ssh4p.api;

/**
 * Bridge for dispatching commands to be executed on the Bukkit main thread.
 * Implementations must ensure the command executes in a thread-safe manner
 * compatible with server expectations.
 */
public interface MainThreadCommandBridge {
	/**
	 * Dispatches a command line to be executed as the server console.
	 *
	 * @param commandLine the command to execute (without leading slash)
	 */
	void dispatchAsConsole(String commandLine);
}
