package dev.maynat.home.listener;

import dev.maynat.home.App;
import dev.maynat.home.gui.MenuManager;
import dev.maynat.home.model.Home;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PlayerListener — Handles player events needed by the home system.
 */
public final class PlayerListener implements Listener {

    private final App plugin;
    private final Map<UUID, String> pendingRename = new ConcurrentHashMap<>();
    private final Pattern homeNamePattern;

    public PlayerListener(App plugin) {
        this.plugin = plugin;
        this.homeNamePattern = Pattern.compile(plugin.getConfigManager()
                .getString("homes.name-pattern", "[a-zA-Z0-9_-]{1,32}"));
    }

    /**
     * Cancels warmup if a player disconnects mid-countdown.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getWarmupManager().cancelWarmup(event.getPlayer().getUniqueId());
        plugin.getCooldownManager().clearCooldown(event.getPlayer());
        pendingRename.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Handles click events inside the Maynat-Home GUI inventories.
     * Click-actions (teleport, close, sub-menu) are dispatched here.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory topInventory = event.getView().getTopInventory();
        if (!plugin.getMenuManager().isManagedMenu(topInventory)) return;

        /* Prevent all interactions with the plugin menu inventory. */
        if (event.getInventory().equals(topInventory)) {
            event.setCancelled(true);
        }

        /* Only handle clicks inside the menu inventory itself. */
        if (!Objects.equals(event.getClickedInventory(), topInventory)) return;

        MenuManager.MenuState state = plugin.getMenuManager().getMenuState(topInventory);
        if (state == null) return;

        int slot = event.getSlot();
        if (state.isHomeSlot(slot)) {
            ClickType click = event.getClick();
            if (event.isLeftClick() || event.isShiftClick()) {
                teleportHome(player, state.getHomeName(slot));
            } else if (event.isRightClick()) {
                plugin.getMenuManager().openConfirmDeleteMenu(player, state.getHomeName(slot));
            } else if (click == ClickType.MIDDLE) {
                startRename(player, state.getHomeName(slot));
            }
            return;
        }

        String action = state.getStaticAction(slot);
        if ("CLOSE".equalsIgnoreCase(action)) {
            player.closeInventory();
            return;
        }
        if ("CANCEL".equalsIgnoreCase(action)) {
            player.closeInventory();
            return;
        }
        if ("PREVIOUS_PAGE".equalsIgnoreCase(action)) {
            int currentPage = state.getCurrentPage();
            if (currentPage > 1) {
                String uuid = player.getUniqueId().toString();
                plugin.getDatabaseManager().getHomes(uuid)
                        .thenCompose(homes -> dev.maynat.home.util.LuckPermsUtil.getHomeLimit(plugin, player)
                                .thenApply(limit -> Map.entry(homes, limit)))
                        .thenAccept(entry -> {
                            Map<String, dev.maynat.home.model.Home> homes = entry.getKey();
                            int limit = entry.getValue();
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    plugin.getMenuManager().openHomesMenu(player, homes, limit, currentPage - 1));
                        });
            }
            return;
        }
        if ("NEXT_PAGE".equalsIgnoreCase(action)) {
            int currentPage = state.getCurrentPage();
            int totalPages = state.getTotalPages();
            if (currentPage < totalPages) {
                String uuid = player.getUniqueId().toString();
                plugin.getDatabaseManager().getHomes(uuid)
                        .thenCompose(homes -> dev.maynat.home.util.LuckPermsUtil.getHomeLimit(plugin, player)
                                .thenApply(limit -> Map.entry(homes, limit)))
                        .thenAccept(entry -> {
                            Map<String, dev.maynat.home.model.Home> homes = entry.getKey();
                            int limit = entry.getValue();
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    plugin.getMenuManager().openHomesMenu(player, homes, limit, currentPage + 1));
                        });
            }
            return;
        }
        if ("CONFIRM_DELETE".equalsIgnoreCase(action)) {
            String targetHome = state.getContextValue("home");
            if (targetHome != null) {
                player.closeInventory();
                deleteHome(player, targetHome);
            }
        }
        if ("OPEN_HELP".equalsIgnoreCase(action)) {
            plugin.getMenuManager().openHelpMenu(player);
            return;
        }
        if ("BACK_TO_HOMES".equalsIgnoreCase(action)) {
            refreshMenu(player);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        plugin.getMenuManager().unregisterMenu(event.getInventory());
    }

    private void teleportHome(Player player, String homeName) {
        if (!player.hasPermission("maynathome.home")) {
            plugin.getMessageUtils().send(player, "general.no-permission");
            return;
        }

        String uuid = player.getUniqueId().toString();
        boolean bypassCooldown = player.hasPermission("maynathome.bypass.cooldown");
        if (!bypassCooldown && plugin.getCooldownManager().isOnCooldown(player)) {
            long remaining = plugin.getCooldownManager().getRemainingSeconds(player);
            plugin.getMessageUtils().send(player, "home.cooldown",
                    Map.of("seconds", String.valueOf(remaining)));
            return;
        }

        plugin.getDatabaseManager().getHome(uuid, homeName)
                .thenAccept(optHome -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (optHome.isEmpty()) {
                        plugin.getMessageUtils().send(player, "home.not-found",
                                Map.of("home", homeName));
                        return;
                    }

                    Home home = optHome.get();
                    int warmupSeconds = plugin.getConfigManager()
                            .getInt("teleport.warmup-seconds", 3);
                    boolean bypassWarmup = player.hasPermission("maynathome.bypass.warmup");

                    if (warmupSeconds <= 0 || bypassWarmup) {
                        player.teleportAsync(home.location()).thenAccept(success ->
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
                                }));
                    } else {
                        plugin.getMessageUtils().send(player, "home.warmup-start",
                                Map.of("home", homeName, "seconds", String.valueOf(warmupSeconds)));
                        plugin.getWarmupManager().startWarmup(player, home, warmupSeconds,
                                () -> player.teleportAsync(home.location()).thenAccept(success ->
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
                                        })));
                    }
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Error fetching home '" + homeName + "' for "
                            + player.getName() + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    private void deleteHome(Player player, String homeName) {
        if (!player.hasPermission("maynathome.delhome")) {
            plugin.getMessageUtils().send(player, "general.no-permission");
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
                            refreshMenu(player);
                        } else {
                            plugin.getMessageUtils().send(player, "home.not-found",
                                    Map.of("home", homeName));
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Error deleting home via menu: " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    private void refreshMenu(Player player) {
        String uuid = player.getUniqueId().toString();
        plugin.getDatabaseManager().getHomes(uuid)
                .thenAccept(homes -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (homes.isEmpty()) {
                        player.closeInventory();
                        plugin.getMessageUtils().send(player, "home.list-empty");
                        return;
                    }
                    plugin.getMenuManager().openHomesMenu(player, homes);
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Error refreshing homes menu: " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String oldHome = pendingRename.get(uuid);
        if (oldHome == null) return;

        event.setCancelled(true);
        String newName = event.getMessage().trim();
        if (newName.isBlank()) {
            plugin.getMessageUtils().send(player, "home.invalid-name");
            return;
        }

        if (newName.equalsIgnoreCase("cancel")) {
            pendingRename.remove(uuid);
            plugin.getMessageUtils().send(player, "home.rename.cancelled");
            return;
        }

        if (!homeNamePattern.matcher(newName).matches()) {
            plugin.getMessageUtils().send(player, "home.invalid-name");
            return;
        }

        if (newName.equalsIgnoreCase(oldHome)) {
            pendingRename.remove(uuid);
            plugin.getMessageUtils().send(player, "home.rename.nochange",
                    Map.of("home", oldHome));
            refreshMenu(player);
            return;
        }

        plugin.getDatabaseManager().renameHome(uuid.toString(), oldHome, newName)
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pendingRename.remove(uuid);
                    switch (result.getStatus()) {
                        case SUCCESS -> {
                            plugin.getHomeTabCompleter().invalidateCache(uuid.toString());
                            plugin.getMessageUtils().send(player, "home.rename.success",
                                    Map.of("old", oldHome, "new", newName));
                            refreshMenu(player);
                        }
                        case NAME_TAKEN -> plugin.getMessageUtils().send(player, "home.rename.exists");
                        case NOT_FOUND -> plugin.getMessageUtils().send(player, "home.not-found",
                                Map.of("home", oldHome));
                        default -> plugin.getMessageUtils().send(player, "general.error");
                    }
                }))
                .exceptionally(ex -> {
                    pendingRename.remove(uuid);
                    plugin.getLogger().severe("Error renaming home from menu: " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getMessageUtils().send(player, "general.error"));
                    return null;
                });
    }

    private void startRename(Player player, String homeName) {
        if (!player.hasPermission(plugin.getConfigManager().getPermission("rename"))) {
            plugin.getMessageUtils().send(player, "general.no-permission");
            return;
        }

        pendingRename.put(player.getUniqueId(), homeName);
        player.closeInventory();
        plugin.getMessageUtils().send(player, "home.rename.prompt",
                Map.of("home", homeName));
    }

    /**
     * Prevents teleport spam by cancelling warmup on Bukkit-triggered teleports
     * that are not initiated by our plugin (e.g., /spawn, /tp by admin).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        var cause = event.getCause();
        /* Allow our own async teleport — cancel warmup for external teleports */
        if (cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            plugin.getWarmupManager().cancelWarmup(event.getPlayer().getUniqueId());
        }
    }
}
