package dev.maynat.home.manager;

import dev.maynat.home.App;
import dev.maynat.home.model.Home;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WarmupManager — Manages teleportation warmup countdowns.
 *
 * <p>Each warmup:</p>
 * <ul>
 *   <li>Records the player's start location</li>
 *   <li>Ticks every second; sends an ActionBar progress bar</li>
 *   <li>Cancels automatically if the player moves beyond the threshold</li>
 *   <li>Executes the provided {@link Runnable} on completion</li>
 * </ul>
 */
public final class WarmupManager {

    private final App plugin;

    /* Active warmup tasks keyed by player UUID */
    private final Map<UUID, WarmupEntry> activeWarmups = new ConcurrentHashMap<>();

    /** Max movement (in blocks) before warmup is cancelled. */
    private static final double MOVE_THRESHOLD_SQ = 0.09; // 0.3 blocks squared

    public WarmupManager(App plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts a warmup countdown for a player.
     * Any existing warmup for this player is cancelled first.
     *
     * @param player         the player
     * @param destination    the home they are teleporting to
     * @param totalSeconds   total warmup duration in seconds
     * @param onComplete     callback executed on successful completion (main thread)
     */
    public void startWarmup(Player player,
                            Home destination,
                            int totalSeconds,
                            Runnable onComplete) {
        UUID uuid = player.getUniqueId();

        /* Cancel any pre-existing warmup */
        cancelWarmup(uuid);

        Location startLocation = player.getLocation().clone();
        int[] secondsLeft = { totalSeconds };

        BukkitTask task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> {

                    /* Player may have disconnected between ticks */
                    if (!player.isOnline()) {
                        cancelWarmup(uuid);
                        return;
                    }

                    /* Movement detection */
                    if (hasMoved(startLocation, player.getLocation())) {
                        cancelWarmup(uuid);
                        plugin.getMessageUtils().send(player, "home.warmup-cancelled");
                        return;
                    }

                    /* Countdown */
                    if (secondsLeft[0] <= 0) {
                        cancelWarmup(uuid);
                        onComplete.run();
                        return;
                    }

                    /* ActionBar progress bar */
                    plugin.getMessageUtils()
                            .sendWarmupBar(player, secondsLeft[0], totalSeconds);

                    secondsLeft[0]--;

                }, 0L, 20L); // start immediately, tick every 20 ticks (1 second)

        activeWarmups.put(uuid, new WarmupEntry(task, startLocation, totalSeconds));
    }

    /**
     * Cancels a player's active warmup, if any.
     */
    public void cancelWarmup(UUID playerUuid) {
        WarmupEntry entry = activeWarmups.remove(playerUuid);
        if (entry != null) {
            entry.task().cancel();
        }
    }

    /**
     * Returns true if the player currently has an active warmup.
     */
    public boolean isInWarmup(Player player) {
        return activeWarmups.containsKey(player.getUniqueId());
    }

    /**
     * Cancels ALL active warmups — called on plugin shutdown.
     */
    public void cancelAll() {
        activeWarmups.values().forEach(entry -> entry.task().cancel());
        activeWarmups.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Checks if the player has moved beyond the allowed threshold.
     * Only XZ movement is checked (vertical movement from falling is ignored).
     */
    private boolean hasMoved(Location from, Location to) {
        if (from.getWorld() != null && !from.getWorld().equals(to.getWorld())) return true;
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return (dx * dx + dz * dz) > MOVE_THRESHOLD_SQ;
    }

    // -------------------------------------------------------------------------

    private record WarmupEntry(BukkitTask task, Location startLocation, int totalSeconds) {}
}
