package dev.anyhomes.model;

import org.bukkit.Location;

import java.util.Objects;

/**
 * Immutable value object representing a single saved home.
 *
 * @param ownerUuid UUID string of the home owner
 * @param name      Lowercase home name (storage key)
 * @param location  Bukkit location (world, xyz, yaw, pitch)
 */
public record Home(
        String ownerUuid,
        String name,
        Location location
) {
    public Home {
        Objects.requireNonNull(ownerUuid, "ownerUuid must not be null");
        Objects.requireNonNull(name,      "name must not be null");
        Objects.requireNonNull(location,  "location must not be null");
    }

    /**
     * Returns a human-readable display name (first letter capitalized).
     */
    public String displayName() {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Returns the world name, or {@code "unknown"} if the world is null.
     */
    public String worldName() {
        return location.getWorld() != null ? location.getWorld().getName() : "unknown";
    }

    /**
     * Formats the XYZ coordinates to one decimal place for display.
     */
    public String formattedCoords() {
        return String.format("%.1f, %.1f, %.1f",
                location.getX(), location.getY(), location.getZ());
    }
}
