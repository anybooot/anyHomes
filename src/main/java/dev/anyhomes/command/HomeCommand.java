package dev.anyhomes.command;

import dev.anyhomes.App;
import dev.anyhomes.model.Home;
import dev.anyhomes.util.LuckPermsUtil;
import net.kyori.adventure.text.Component;
import net.luckperms.api.model.user.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * HomeCommand — Routes sub-commands for {@code /maynat-home}.
 *
 * <p>Sub-command table:</p>
 * <ul>
 *   <li>{@code sethome [name]}  — Saves current location as a home</li>
 *   <li>{@code home [name]}     — Teleports to a saved home</li>
 *   <li>{@code delhome <name>}  — Deletes a saved home</li>
 *   <li>{@code list}            — Lists all saved homes (or opens GUI)</li>
 *   <li>{@code admin}           — Administrative sub-commands</li>
 * </ul>
 *
 * <p>All heavy operations (DB reads, LuckPerms lookups) are performed
 * asynchronously. Teleportation and message delivery are then dispatched
 * back to the main thread via {@code Bukkit.getScheduler().runTask(…)}.</p>
 */
public final class HomeCommand implements CommandExecutor {

    private final App plugin;

    /* Default home name when none is specified */
    private static final String DEFAULT_HOME_NAME = "home";

    public HomeCommand(App plugin) {
        this.plugin = plugin;
    }

    private boolean isValidHomeName(String homeName) {
        String pattern = plugin.getConfigManager()
                .getString("homes.name-pattern", "[a-zA-Z0-9_-]{1,32}");
        return homeName != null && homeName.matches(pattern);
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            plugin.getMessageUtils().send(sender, "general.player-only");
            return true;
        }

        if (args.length == 0) {
            /* Default: open homes menu */
            handleListHomes(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "sethome" -> handleSetHome(player, args.length > 1 ? args[1] : DEFAULT_HOME_NAME);
            case "sethomes" -> {
                if (args.length < 2) {
                    plugin.getMessageUtils().send(player, "admin.sethomes-usage");
                    return true;
                }
                handleAdminSetHome(player, args[1]);
            }
            case "home" -> {
                if (args.length > 1 && args[1].contains(":")) {
                    handleAdminListOrTeleport(player, args[1]);
                } else {
                    handleGoHome(player, args.length > 1 ? args[1] : DEFAULT_HOME_NAME);
                }
            }
            case "homes" -> {
                if (args.length > 1) {
                    handleAdminListOrTeleport(player, args[1]);
                } else {
                    handleListHomes(player);
                }
            }
            case "delhome" -> {
                if (args.length < 2) {
                    plugin.getMessageUtils().send(player, "home.delhome-usage");
                    return true;
                }
                if (args[1].contains(":")) {
                    handleAdminDeleteHome(player, args[1]);
                } else {
                    handleDeleteHome(player, args[1]);
                }
            }
            case "delhomes" -> {
                if (args.length < 2) {
                    plugin.getMessageUtils().send(player, "admin.delhomes-usage");
                    return true;
                }
                handleAdminDeleteHome(player, args[1]);
            }
            case "list"    -> handleListHomes(player);
            case "reload" -> {
                if (!player.hasPermission(plugin.getConfigManager().getPermission("admin"))) {
                    plugin.getMessageUtils().send(player, "general.no-permission");
                    return true;
                }
                plugin.getConfigManager().load();
                plugin.getMenuManager().loadMenus();
                plugin.getMessageUtils().send(player, "admin.reloaded");
            }
            default        -> plugin.getMessageUtils().send(player, "general.unknown-command");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // /sethome [name]
    // -------------------------------------------------------------------------

    /**
     * Handles home creation with LuckPerms limit validation.
     *
     * <p>Flow (all async until the final message dispatch):</p>
     * <ol>
     *   <li>Resolve home limit from LuckPerms meta-key {@code home-limit}</li>
     *   <li>Count existing homes from the database</li>
     *   <li>If under limit → save; else → send limit message</li>
     * </ol>
     */
    private void handleSetHome(Player player, String homeName) {
        if (!isValidHomeName(homeName)) {
            plugin.getMessageUtils().send(player, "home.invalid-name");
            return;
        }

        if (!player.hasPermission(plugin.getConfigManager().getPermission("sethome"))) {
            plugin.getMessageUtils().send(player, "general.no-permission");
            return;
        }

        String uuid  = player.getUniqueId().toString();
        var    loc   = player.getLocation().clone(); // clone to avoid mutation

        /* Resolve limit asynchronously (LuckPerms + DB) */
        LuckPermsUtil.getHomeLimit(plugin, player).thenCompose(limit -> {
            /* Check bypass for unlimited homes */
            if (player.hasPermission(plugin.getConfigManager().getBypassPermission("limit"))) {
                return plugin.getDatabaseManager()
                        .saveHome(uuid, homeName, loc)
                        .thenApply(saved -> new SetHomeResult(saved, -1, -1));
            }
            return plugin.getDatabaseManager().countHomes(uuid)
                    .thenCompose(count -> {
                        if (count >= limit) {
                            return java.util.concurrent.CompletableFuture
                                    .completedFuture(new SetHomeResult(false, count, limit));
                        }
                        return plugin.getDatabaseManager()
                                .saveHome(uuid, homeName, loc)
                                .thenApply(saved -> new SetHomeResult(saved, count + 1, limit));
                    });

        }).thenAccept(result -> {
            /* Dispatch back to main thread for message delivery */
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!result.saved()) {
                    if (result.limit() > 0 && result.current() >= result.limit()) {
                        plugin.getMessageUtils().send(player, "home.limit-reached",
                                Map.of("limit", String.valueOf(result.limit())));
                    } else {
                        plugin.getMessageUtils().send(player, "home.set-failed");
                    }
                    return;
                }
                plugin.getHomeTabCompleter().invalidateCache(uuid);
                plugin.getMessageUtils().send(player, "home.set",
                        Map.of("home", homeName));
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Error in sethome for " + player.getName() + ": " + ex.getMessage());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getMessageUtils().send(player, "general.error"));
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // /home [name]
    // -------------------------------------------------------------------------

    /**
     * Handles teleportation to a named home with warmup and cooldown support.
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Check cooldown (skip if bypass permission)</li>
     *   <li>Fetch home from DB asynchronously</li>
     *   <li>Start warmup countdown via {@link dev.anyhomes.manager.WarmupManager}</li>
     *   <li>After warmup: teleport using Paper's async teleport API</li>
     *   <li>Apply cooldown</li>
     * </ol>
     */
    private void handleGoHome(Player player, String homeName) {
        if (!player.hasPermission(plugin.getConfigManager().getPermission("home"))) {
            plugin.getMessageUtils().send(player, "general.no-permission");
            return;
        }

        String uuid = player.getUniqueId().toString();

        /* ── Cooldown check ── */
        boolean bypassCooldown = player.hasPermission(plugin.getConfigManager().getBypassPermission("cooldown"));
        if (!bypassCooldown && plugin.getCooldownManager().isOnCooldown(player)) {
            long remaining = plugin.getCooldownManager().getRemainingSeconds(player);
            plugin.getMessageUtils().send(player, "home.cooldown",
                    Map.of("seconds", String.valueOf(remaining)));
            return;
        }

        /* ── Fetch home from DB async ── */
        plugin.getDatabaseManager().getHome(uuid, homeName)
                .thenAccept(optHome -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (optHome.isEmpty()) {
                            plugin.getMessageUtils().send(player, "home.not-found",
                                    Map.of("home", homeName));
                            return;
                        }

                        Home home = optHome.get();
                        int warmupSeconds = plugin.getConfigManager()
                                .getInt("teleport.warmup-seconds", 3);
                        boolean bypassWarmup = player.hasPermission(plugin.getConfigManager().getBypassPermission("warmup"));

                        if (warmupSeconds <= 0 || bypassWarmup) {
                            /* Instant teleport */
                            performTeleport(player, home, bypassCooldown);
                        } else {
                            /* Warmup countdown */
                            plugin.getMessageUtils().send(player, "home.warmup-start",
                                    Map.of("home", homeName, "seconds", String.valueOf(warmupSeconds)));
                            plugin.getWarmupManager().startWarmup(player, home, warmupSeconds,
                                    () -> performTeleport(player, home, bypassCooldown));
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Error fetching home '" + homeName
                            + "' for " + player.getName() + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    /**
     * Executes the actual teleport using Paper's async teleportation API.
     * This avoids chunk-load lag on the main thread.
     */
    private void performTeleport(Player player, Home home, boolean bypassCooldown) {
        /* Paper async teleport — handles chunk loading automatically */
        player.teleportAsync(home.location()).thenAccept(success -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (success) {
                    plugin.getMessageUtils().send(player, "home.teleported",
                            Map.of("home", home.displayName()));
                    if (!bypassCooldown) {
                        int cooldownSeconds = plugin.getConfigManager()
                                .getInt("teleport.cooldown-seconds", 30);
                        plugin.getCooldownManager().setCooldown(player, cooldownSeconds);
                    }
                } else {
                    plugin.getMessageUtils().send(player, "home.teleport-failed");
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // /delhome <name>
    // -------------------------------------------------------------------------

    private void handleDeleteHome(Player player, String homeName) {
        if (!player.hasPermission(plugin.getConfigManager().getPermission("delhome"))) {
            plugin.getMessageUtils().send(player, "general.no-permission");
            return;
        }

        boolean guiEnabled = plugin.getConfigManager().getBoolean("gui.homes-list-enabled", true);
        if (guiEnabled) {
            plugin.getMenuManager().openConfirmDeleteMenu(player, homeName);
            return;
        }

        String uuid = player.getUniqueId().toString();

        plugin.getDatabaseManager().deleteHome(uuid, homeName)
                .thenAccept(deleted -> {
                    if (deleted) {
                        plugin.getHomeTabCompleter().invalidateCache(uuid);
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (deleted) {
                            plugin.getMessageUtils().send(player, "home.deleted",
                                    Map.of("home", homeName));
                        } else {
                            plugin.getMessageUtils().send(player, "home.not-found",
                                    Map.of("home", homeName));
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Error deleting home: " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // /list
    // -------------------------------------------------------------------------

    private void handleListHomes(Player player) {
        if (!player.hasPermission(plugin.getConfigManager().getPermission("list"))) {
            plugin.getMessageUtils().send(player, "general.no-permission");
            return;
        }

        String uuid = player.getUniqueId().toString();

        plugin.getDatabaseManager().getHomes(uuid)
                .thenCompose(homes -> LuckPermsUtil.getHomeLimit(plugin, player)
                        .thenApply(limit -> Map.entry(homes, limit)))
                .thenAccept(entry -> {
                    Map<String, Home> homes = entry.getKey();
                    int limit = entry.getValue();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (homes.isEmpty()) {
                            plugin.getMessageUtils().send(player, "home.list-empty");
                            return;
                        }

                        /* If GUI is configured, open the homes menu instead */
                        boolean guiEnabled = plugin.getConfigManager()
                                .getBoolean("gui.homes-list-enabled", true);

                        if (guiEnabled) {
                            plugin.getMenuManager().openHomesMenu(player, homes, limit);
                        } else {
                            /* Text list fallback */
                            plugin.getMessageUtils().send(player, "home.list-header",
                                    Map.of("count", String.valueOf(homes.size())));
                            homes.values().forEach(home ->
                                    plugin.getMessageUtils().send(player, "home.list-entry",
                                            Map.of(
                                                    "home",   home.displayName(),
                                                    "world",  home.worldName(),
                                                    "coords", home.formattedCoords()
                                            )));
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Error listing homes: " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // Admin Commands
    // -------------------------------------------------------------------------

    private void handleAdminListOrTeleport(Player admin, String arg) {
        if (!admin.hasPermission(plugin.getConfigManager().getPermission("admin"))) {
            plugin.getMessageUtils().send(admin, "general.no-permission");
            return;
        }

        String targetName;
        String homeName = null;

        if (arg.contains(":")) {
            String[] parts = arg.split(":", 2);
            targetName = parts[0];
            if (parts.length > 1 && !parts[1].isBlank()) {
                homeName = parts[1];
            }
        } else {
            targetName = arg;
        }

        final String finalTargetName = targetName;
        final String finalHomeName = homeName;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var target = plugin.getServer().getOfflinePlayerIfCached(finalTargetName);
            if (target == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getMessageUtils().send(admin, "admin.player-not-found",
                                Map.of("player", finalTargetName)));
                return;
            }

            String targetUuid = target.getUniqueId().toString();

            if (finalHomeName == null) {
                /* List all homes for the player */
                plugin.getDatabaseManager().getHomes(targetUuid)
                        .thenAccept(homes -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (homes.isEmpty()) {
                                plugin.getMessageUtils().send(admin, "admin.player-no-homes",
                                        Map.of("player", finalTargetName));
                                return;
                            }

                            plugin.getMessageUtils().send(admin, "admin.player-homes-header",
                                    Map.of("player", finalTargetName, "count", String.valueOf(homes.size())));
                            homes.values().forEach(h ->
                                    plugin.getMessageUtils().send(admin, "home.list-entry",
                                            Map.of("home", h.displayName(),
                                                   "world", h.worldName(),
                                                   "coords", h.formattedCoords())));
                        }));
            } else {
                /* Teleport to specific home */
                plugin.getDatabaseManager().getHome(targetUuid, finalHomeName)
                        .thenAccept(optHome -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (optHome.isEmpty()) {
                                plugin.getMessageUtils().send(admin, "admin.home-not-found",
                                        Map.of("player", finalTargetName, "home", finalHomeName));
                                return;
                            }

                            Home home = optHome.get();
                            admin.teleportAsync(home.location()).thenAccept(success -> {
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (success) {
                                        plugin.getMessageUtils().send(admin, "admin.teleported-to-home",
                                                Map.of("player", finalTargetName, "home", home.displayName()));
                                    } else {
                                        plugin.getMessageUtils().send(admin, "home.teleport-failed");
                                    }
                                });
                            });
                        }));
            }
        });
    }

    private void handleAdminDeleteHome(Player admin, String arg) {
        if (!admin.hasPermission(plugin.getConfigManager().getPermission("admin"))) {
            plugin.getMessageUtils().send(admin, "general.no-permission");
            return;
        }

        if (!arg.contains(":")) {
            plugin.getMessageUtils().send(admin, "admin.delhomes-usage");
            return;
        }

        String[] parts = arg.split(":", 2);
        String targetName = parts[0];
        String homeName = parts.length > 1 ? parts[1] : "";

        if (homeName.isBlank()) {
            plugin.getMessageUtils().send(admin, "admin.delhomes-usage");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var target = plugin.getServer().getOfflinePlayerIfCached(targetName);
            if (target == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getMessageUtils().send(admin, "admin.player-not-found",
                                Map.of("player", targetName)));
                return;
            }
            String targetUuid = target.getUniqueId().toString();
            plugin.getDatabaseManager()
                    .deleteHome(targetUuid, homeName)
                    .thenAccept(deleted -> {
                        if (deleted) {
                            plugin.getHomeTabCompleter().invalidateCache(targetUuid);
                        }
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.getMessageUtils().send(admin,
                                        deleted ? "admin.home-deleted" : "admin.home-not-found",
                                        Map.of("player", targetName, "home", homeName)));
                    });
        });
    }

    private void handleAdminSetHome(Player admin, String arg) {
        if (!admin.hasPermission(plugin.getConfigManager().getPermission("admin"))) {
            plugin.getMessageUtils().send(admin, "general.no-permission");
            return;
        }

        if (!arg.contains(":")) {
            plugin.getMessageUtils().send(admin, "admin.sethomes-usage");
            return;
        }

        String[] parts = arg.split(":", 2);
        String targetName = parts[0];
        String homeName = parts.length > 1 ? parts[1] : "";

        if (homeName.isBlank()) {
            plugin.getMessageUtils().send(admin, "admin.sethomes-usage");
            return;
        }

        if (!isValidHomeName(homeName)) {
            plugin.getMessageUtils().send(admin, "home.invalid-name");
            return;
        }

        var loc = admin.getLocation().clone();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var target = plugin.getServer().getOfflinePlayerIfCached(targetName);
            if (target == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getMessageUtils().send(admin, "admin.player-not-found",
                                Map.of("player", targetName)));
                return;
            }
            String targetUuid = target.getUniqueId().toString();
            plugin.getDatabaseManager()
                    .saveHome(targetUuid, homeName, loc)
                    .thenAccept(saved -> {
                        if (saved) {
                            plugin.getHomeTabCompleter().invalidateCache(targetUuid);
                        }
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (saved) {
                                plugin.getMessageUtils().send(admin, "admin.home-set",
                                        Map.of("player", targetName, "home", homeName));
                            } else {
                                plugin.getMessageUtils().send(admin, "home.set-failed");
                            }
                        });
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Internal Records
    // -------------------------------------------------------------------------

    /**
     * Carries the result of a sethome operation between async stages.
     */
    private record SetHomeResult(boolean saved, int current, int limit) {}
}
