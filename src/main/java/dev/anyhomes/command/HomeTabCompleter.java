package dev.anyhomes.command;

import dev.anyhomes.App;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Context-aware tab completer for {@code /maynat-home}.
 *
 * <p>Home names for the current player are pre-fetched asynchronously and
 * cached briefly so that tab-completion never blocks the main thread.</p>
 */
public final class HomeTabCompleter implements TabCompleter {

    private final App plugin;

    /* Simple TTL cache: uuid → (names, fetchTimeNanos) */
    private final Map<String, CachedNames> nameCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final List<String> SUB_COMMANDS =
            List.of("sethome", "sethomes", "home", "delhome", "delhomes", "homes", "list", "reload");

    public HomeTabCompleter(App plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();

        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            boolean isAdmin = player.hasPermission(plugin.getConfigManager().getPermission("admin"));
            switch (sub) {
                case "home", "delhome" -> {
                    if (isAdmin && !args[1].isEmpty()) {
                        String[] parts = args[1].split(":");
                        if (parts.length > 0 && !parts[0].isEmpty()) {
                            if (args[1].contains(":") || plugin.getServer().getOfflinePlayerIfCached(parts[0]) != null) {
                                return getAdminHomeSuggestions(args[1]);
                            }
                        }
                    }
                    return filter(getHomeNames(player), args[1]);
                }
                case "homes", "delhomes", "sethomes" -> {
                    if (isAdmin) {
                        return getAdminHomeSuggestions(args[1]);
                    }
                }
            }
        }

        return List.of();
    }

    private List<String> getAdminHomeSuggestions(String currentToken) {
        List<String> suggestions = new ArrayList<>();
        if (!currentToken.contains(":")) {
            String lower = currentToken.toLowerCase();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(lower)) {
                    suggestions.add(p.getName() + ":");
                }
            }
        } else {
            String[] parts = currentToken.split(":", 2);
            String targetName = parts[0];
            String homePrefix = parts.length > 1 ? parts[1] : "";

            var target = plugin.getServer().getOfflinePlayerIfCached(targetName);
            if (target != null) {
                String targetUuid = target.getUniqueId().toString();
                List<String> homeNames = getHomeNamesForUuid(targetUuid);
                String lowerPrefix = homePrefix.toLowerCase();
                for (String name : homeNames) {
                    if (name.toLowerCase().startsWith(lowerPrefix)) {
                        suggestions.add(targetName + ":" + name);
                    }
                }
            }
        }
        return suggestions;
    }

    // -------------------------------------------------------------------------
    // Cache Helpers
    // -------------------------------------------------------------------------

    private List<String> getHomeNames(Player player) {
        return getHomeNamesForUuid(player.getUniqueId().toString());
    }

    private List<String> getHomeNamesForUuid(String uuid) {
        CachedNames cached = nameCache.get(uuid);

        long now = System.nanoTime();
        if (cached == null || (now - cached.fetchedAt) > CACHE_TTL_NANOS) {
            /* Async refresh — returns stale data this time, fresh next time */
            plugin.getDatabaseManager().getHomes(uuid).thenAccept(homes ->
                    nameCache.put(uuid, new CachedNames(
                            new ArrayList<>(homes.keySet()), System.nanoTime())));
            return cached != null ? cached.names : List.of();
        }
        return cached.names;
    }

    public void invalidateCache(String uuid) {
        nameCache.remove(uuid);
    }

    private List<String> filter(List<String> source, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String s : source) {
            if (s.toLowerCase().startsWith(lower)) result.add(s);
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private record CachedNames(List<String> names, long fetchedAt) {}
}
