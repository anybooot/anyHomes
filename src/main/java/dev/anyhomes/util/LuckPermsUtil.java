package dev.anyhomes.util;

import dev.anyhomes.App;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * LuckPermsUtil — Utilities for fetching LuckPerms data asynchronously.
 */
public final class LuckPermsUtil {

    private LuckPermsUtil() {}

    /**
     * Returns the highest home limit granted by explicit permissions.
     *
     * <p>Permissions follow the pattern {@code maynat-home.sethome.<number>}.
     * The default limit from config is always preserved if no higher
     * permission is present.</p>
     */
    private static int getPermissionHomeLimit(Player player, int configDefault) {
        int highest = configDefault;
        for (int i = configDefault + 1; i <= 1000; i++) {
            if (player.hasPermission("maynat-home.sethome." + i)) {
                highest = i;
            }
        }
        return highest;
    }

    /**
     * Resolves the home limit for a player by reading the LuckPerms
     * meta-key {@code home-limit} and explicit permissions.
     *
     * <p>Players may exceed the default limit if they have a permission
     * like {@code maynat-home.sethome.5}. The default limit remains
     * {@code homes.default-limit} from config when no such permission is set.</p>
     *
     * @param plugin the plugin instance
     * @param player the online player
     * @return a future resolving to the home limit (integer)
     */
    public static CompletableFuture<Integer> getHomeLimit(App plugin, Player player) {
        int configDefault = plugin.getConfigManager()
                .getInt("homes.default-limit", 3);

        int permissionLimit = getPermissionHomeLimit(player, configDefault);
        if (plugin.getLuckPerms() == null) {
            return CompletableFuture.completedFuture(permissionLimit);
        }

        return plugin.getLuckPerms().getUserManager()
                .loadUser(player.getUniqueId())
                .thenApply(user -> {
                    int limit = permissionLimit;
                    if (user == null) return limit;

                    String metaValue = user.getCachedData()
                            .getMetaData()
                            .getMetaValue("home-limit");

                    if (metaValue == null) return limit;

                    try {
                        int metaLimit = Integer.parseInt(metaValue);
                        if (metaLimit > configDefault && player.hasPermission("maynat-home.sethome." + metaLimit)) {
                            limit = Math.max(limit, metaLimit);
                        }
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning(
                                "Invalid home-limit meta value '" + metaValue
                                + "' for player " + player.getName()
                                + " — using config/default limit (" + configDefault + ")");
                    }
                    return limit;
                });
    }
}
