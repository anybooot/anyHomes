package dev.maynat.home.manager;

import dev.maynat.home.App;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CooldownManager — Tracks per-player teleport cooldowns in memory.
 *
 * <p>Cooldowns are stored as a map of UUID → expiry time (milliseconds).
 * The data is intentionally volatile (not persisted) because cooldowns
 * reset on server restart is standard behaviour.</p>
 */
public final class CooldownManager {

    private final App plugin;

    /* UUID → System.currentTimeMillis() at which the cooldown expires */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public CooldownManager(App plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks whether a player is currently on cooldown.
     */
    public boolean isOnCooldown(Player player) {
        Long expiry = cooldowns.get(player.getUniqueId());
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            cooldowns.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /**
     * Returns the remaining cooldown time in seconds, or 0 if not on cooldown.
     */
    public long getRemainingSeconds(Player player) {
        Long expiry = cooldowns.get(player.getUniqueId());
        if (expiry == null) return 0L;
        long diff = expiry - System.currentTimeMillis();
        return diff > 0 ? (long) Math.ceil(diff / 1000.0) : 0L;
    }

    /**
     * Applies a cooldown to a player for the given duration.
     *
     * @param player  the player
     * @param seconds duration in seconds
     */
    public void setCooldown(Player player, int seconds) {
        cooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + (seconds * 1000L));
    }

    /**
     * Clears the cooldown for a player immediately.
     */
    public void clearCooldown(Player player) {
        cooldowns.remove(player.getUniqueId());
    }
}
