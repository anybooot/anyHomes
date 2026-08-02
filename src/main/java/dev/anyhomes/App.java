package dev.anyhomes;

import com.zaxxer.hikari.HikariDataSource;
import dev.anyhomes.command.HomeCommand;
import dev.anyhomes.command.HomeTabCompleter;
import dev.anyhomes.database.DatabaseManager;
import dev.anyhomes.gui.MenuManager;
import dev.anyhomes.listener.PlayerListener;
import dev.anyhomes.manager.CooldownManager;
import dev.anyhomes.manager.WarmupManager;
import dev.anyhomes.util.ConfigManager;
import dev.anyhomes.util.MessageUtils;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * AnyHomes — High-End Home Management System
 * Main plugin entry point. Handles lifecycle, dependency injection,
 * and bootstrapping of all subsystems.
 *
 * @author AnyHomes Development Team
 * @version 1.0.0
 */
public final class App extends JavaPlugin {

    private static App instance;

    /* ── Core Subsystems ── */
    private ConfigManager configManager;
    private MessageUtils messageUtils;
    private DatabaseManager databaseManager;
    private MenuManager menuManager;
    private CooldownManager cooldownManager;
    private WarmupManager warmupManager;
    private HomeTabCompleter homeTabCompleter;

    /* ── Soft-Dependency APIs ── */
    private LuckPerms luckPerms;
    private boolean placeholderApiEnabled = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("╔═══════════════════════════════════════╗");
        getLogger().info("║          AnyHomes  v" + getDescription().getVersion() + "             ║");
        getLogger().info("║    High-End Home Management System    ║");
        getLogger().info("╚═══════════════════════════════════════╝");

        /* 1. Load configuration files */
        if (!loadConfigurations()) {
            getLogger().severe("Failed to load configurations. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        /* 2. Resolve soft-dependencies */
        resolveLuckPerms();
        resolvePlaceholderAPI();

        /* 3. Initialize core utilities */
        messageUtils = new MessageUtils(this);

        /* 4. Initialize database (async-safe, blocks on first connect only) */
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Database initialization failed. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        /* 5. Build runtime managers */
        cooldownManager = new CooldownManager(this);
        warmupManager   = new WarmupManager(this);

        /* 6. Load GUI menus from /menus/ folder */
        menuManager = new MenuManager(this);
        menuManager.loadMenus();

        /* 7. Register commands */
        registerCommands();

        /* 8. Register listeners */
        registerListeners();

        getLogger().info("AnyHomes has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        /* Cancel all pending warmup tasks gracefully */
        if (warmupManager != null) {
            warmupManager.cancelAll();
        }

        /* Flush and close the database connection pool */
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("AnyHomes has been disabled. Goodbye!");
        instance = null;
    }

    // -------------------------------------------------------------------------
    // Initialization Helpers
    // -------------------------------------------------------------------------

    /**
     * Saves and loads config.yml and messages.yml from the plugin data folder.
     *
     * @return true if both files loaded without error
     */
    private boolean loadConfigurations() {
        try {
            configManager = new ConfigManager(this);
            configManager.load();
            return true;
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Error loading configuration files", ex);
            return false;
        }
    }

    /**
     * Attempts to hook into the LuckPerms API via the Bukkit service provider.
     * LuckPerms is a hard soft-dependency — home limits rely on it.
     */
    private void resolveLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider =
                getServer().getServicesManager().getRegistration(LuckPerms.class);

        if (provider != null) {
            luckPerms = provider.getProvider();
            getLogger().info("LuckPerms API hooked successfully.");
        } else {
            getLogger().warning(
                    "LuckPerms not found! Home limits will fall back to config defaults.");
        }
    }

    /**
     * Checks if PlaceholderAPI is present on the server.
     * All message parsing degrades gracefully if PAPI is absent.
     */
    private void resolvePlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderApiEnabled = true;
            getLogger().info("PlaceholderAPI hooked successfully.");
        } else {
            getLogger().info("PlaceholderAPI not found. PAPI placeholders will be skipped.");
        }
    }

    /**
     * Registers the main /anyhomes command and all aliases via plugin.yml.
     */
    private void registerCommands() {
        HomeCommand homeCommand = new HomeCommand(this);
        homeTabCompleter = new HomeTabCompleter(this);

        var cmd = getCommand("anyhomes");
        if (cmd != null) {
            cmd.setExecutor(homeCommand);
            cmd.setTabCompleter(homeTabCompleter);
        }

        /* Register short-command variants that delegate to HomeCommand */
        registerShortCommand("sethome", homeCommand, homeTabCompleter);
        registerShortCommand("sethomes", homeCommand, homeTabCompleter);
        registerShortCommand("home", homeCommand, homeTabCompleter);
        registerShortCommand("delhome", homeCommand, homeTabCompleter);
        registerShortCommand("delhomes", homeCommand, homeTabCompleter);
        registerShortCommand("homes", homeCommand, homeTabCompleter);
    }

    /**
     * Registers a short command (like /home) that delegates to HomeCommand
     * with the command name prepended as the first argument.
     */
    private void registerShortCommand(String cmdName, HomeCommand executor, HomeTabCompleter completer) {
        var cmd = getCommand(cmdName);
        if (cmd != null) {
            cmd.setExecutor((sender, command, label, args) -> {
                /* Map empty /home or /homes to list so the menu opens. */
                String[] newArgs;
                if ((cmdName.equalsIgnoreCase("home") || cmdName.equalsIgnoreCase("homes"))
                        && (args.length == 0 || (args.length == 1 && args[0].isBlank()))) {
                    newArgs = new String[]{"list"};
                } else {
                    newArgs = new String[args.length + 1];
                    newArgs[0] = cmdName;
                    System.arraycopy(args, 0, newArgs, 1, args.length);
                }
                return executor.onCommand(sender, command, label, newArgs);
            });
            cmd.setTabCompleter((sender, command, label, args) -> {
                /* Prepend the command name to args for tab completion */
                String[] newArgs = new String[args.length + 1];
                newArgs[0] = cmdName;
                System.arraycopy(args, 0, newArgs, 1, args.length);
                return completer.onTabComplete(sender, command, label, newArgs);
            });
        }
    }

    public HomeTabCompleter getHomeTabCompleter() {
        return homeTabCompleter;
    }

    /**
     * Registers all Bukkit event listeners.
     */
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new PlayerListener(this), this);
    }

    // -------------------------------------------------------------------------
    // Static Accessor
    // -------------------------------------------------------------------------

    /**
     * Returns the singleton plugin instance.
     *
     * @return the App instance
     */
    public static App getInstance() {
        return instance;
    }

    // -------------------------------------------------------------------------
    // Dependency Getters (Dependency Injection via constructor in subsystems)
    // -------------------------------------------------------------------------

    public ConfigManager getConfigManager()       { return configManager; }
    public MessageUtils getMessageUtils()          { return messageUtils; }
    public DatabaseManager getDatabaseManager()    { return databaseManager; }
    public MenuManager getMenuManager()            { return menuManager; }
    public CooldownManager getCooldownManager()    { return cooldownManager; }
    public WarmupManager getWarmupManager()        { return warmupManager; }
    public LuckPerms getLuckPerms()                { return luckPerms; }
    public boolean isPlaceholderApiEnabled()       { return placeholderApiEnabled; }
}
