package dev.anyhomes.util;

import dev.anyhomes.App;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * ConfigManager — Manages {@code config.yml} and {@code messages.yml}.
 *
 * <p>Both files are saved from the bundled defaults if not present,
 * and can be hot-reloaded at runtime via {@code /maynat-home admin reload}.</p>
 */
public final class ConfigManager {

    private final App plugin;

    private FileConfiguration config;
    private FileConfiguration messages;

    private File messagesFile;

    public ConfigManager(App plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads (or reloads) both configuration files from disk.
     * Missing files are created from the bundled resource defaults.
     */
    public void load() {
        /* config.yml */
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        /* messages.yml */
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        /* Merge defaults from bundled messages.yml */
        InputStream defaultMessagesStream = plugin.getResource("messages.yml");
        if (defaultMessagesStream != null) {
            YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultMessagesStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaultMessages);
        }
    }

    // -------------------------------------------------------------------------
    // config.yml accessors
    // -------------------------------------------------------------------------

    public String getString(String path, String def)   { return config.getString(path, def); }
    public int    getInt(String path, int def)          { return config.getInt(path, def); }
    public boolean getBoolean(String path, boolean def) { return config.getBoolean(path, def); }
    public double getDouble(String path, double def)    { return config.getDouble(path, def); }

    /**
     * Retrieves a permission string from the permissions section.
     *
     * @param key the permission key (e.g., "sethome")
     * @return the configured permission, or default if not set
     */
    public String getPermission(String key) {
        return getString("permissions." + key, "maynat-home." + key);
    }

    /**
     * Retrieves a bypass permission string.
     *
     * @param key the bypass key (e.g., "limit")
     * @return the configured bypass permission
     */
    public String getBypassPermission(String key) {
        return getString("permissions.bypass." + key, "maynat-home.bypass." + key);
    }

    // -------------------------------------------------------------------------
    // messages.yml accessor
    // -------------------------------------------------------------------------

    /**
     * Retrieves a raw MiniMessage string from {@code messages.yml}.
     *
     * @param key dot-notation key
     * @return the raw string, or a fallback error string if missing
     */
    public String getMessage(String key) {
        String value = messages.getString(key);
        if (value == null) {
            plugin.getLogger().warning("Missing message key: '" + key + "'");
            return "<red>Missing message: " + key + "</red>";
        }
        return value;
    }

    public FileConfiguration getRawConfig()    { return config; }
    public FileConfiguration getRawMessages()  { return messages; }
}
