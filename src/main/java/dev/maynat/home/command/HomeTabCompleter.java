package dev.maynat.home.command;

import dev.maynat.home.App;
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
            List.of("sethome", "home", "delhome", "list", "admin");

    private static final List<String> ADMIN_SUB_COMMANDS =
            List.of("reload", "delhome", "home");

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
            switch (sub) {
                case "home", "delhome" -> {
                    return filter(getHomeNames(player), args[1]);
                }
                case "admin" -> {
                    if (player.hasPermission("maynathome.admin")) {
                        return filter(ADMIN_SUB_COMMANDS, args[1]);
                    }
                }
            }
        }

        /* /maynat-home admin delhome <player> → online player names */
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("delhome")
                && player.hasPermission("maynathome.admin")) {
            List<String> online = new ArrayList<>();
            plugin.getServer().getOnlinePlayers()
                    .forEach(p -> online.add(p.getName()));
            return filter(online, args[2]);
        }

        /* /maynat-home admin home <player> → online player names */
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("home")
                && player.hasPermission("maynathome.admin")) {
            List<String> online = new ArrayList<>();
            plugin.getServer().getOnlinePlayers()
                    .forEach(p -> online.add(p.getName()));
            return filter(online, args[2]);
        }

        return List.of();
    }

    // -------------------------------------------------------------------------
    // Cache Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns cached home names for a player, triggering an async refresh
     * if the cache is stale or empty.
     */
    private List<String> getHomeNames(Player player) {
        String uuid = player.getUniqueId().toString();
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
