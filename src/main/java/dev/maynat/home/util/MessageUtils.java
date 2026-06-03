package dev.maynat.home.util;

import dev.maynat.home.App;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

/**
 * MessageUtils — Central message parsing and delivery utility.
 *
 * <p>All strings run through the following pipeline:</p>
 * <ol>
 *   <li>Load raw string from {@code messages.yml} via key</li>
 *   <li>Resolve PlaceholderAPI placeholders (if PAPI is present)</li>
 *   <li>Parse MiniMessage tags (supports full HEX + gradients)</li>
 *   <li>Deliver to the target {@link Audience}</li>
 * </ol>
 *
 * <p>Usage example:
 * <pre>{@code
 *   plugin.getMessageUtils().send(player, "home.set",
 *       Map.of("home", homeName));
 * }</pre>
 * </p>
 */
public final class MessageUtils {

    private final App plugin;
    private final MiniMessage miniMessage;

    private static final Map<Character, String> LEGACY_CODE_TO_MINI_MESSAGE = Map.ofEntries(
            Map.entry('0', "<black>"),
            Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"),
            Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"),
            Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"),
            Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"),
            Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"),
            Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"),
            Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    public MessageUtils(App plugin) {
        this.plugin     = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    // -------------------------------------------------------------------------
    // Core Parse Methods
    // -------------------------------------------------------------------------

    /**
     * Parses a raw MiniMessage string with optional PAPI + custom placeholders.
     *
     * @param sender      the command sender (used for PAPI context), may be null
     * @param rawMessage  the raw MiniMessage string
     * @param placeholders key-value pairs of custom {@code <placeholder>} tags
     * @return the parsed Adventure {@link Component}
     */
    public Component parse(CommandSender sender,
                            String rawMessage,
                            Map<String, String> placeholders) {
        /* Step 1: PAPI substitution (requires a Player context) */
        String processed = rawMessage;
        if (plugin.isPlaceholderApiEnabled() && sender instanceof Player player) {
            processed = PlaceholderAPI.setPlaceholders(player, processed);
        }

        /* Step 1b: convert legacy formatting codes like &f to MiniMessage tags */
        processed = convertLegacyMiniMessageCodes(processed);

        /* Step 2: Add built-in placeholders like <prefix> */
        TagResolver.Builder resolverBuilder = TagResolver.builder();
        resolverBuilder.resolver(
                Placeholder.parsed("prefix", plugin.getConfigManager().getMessage("prefix")));
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                resolverBuilder.resolver(
                        Placeholder.parsed(entry.getKey(), entry.getValue()));
            }
        }

        /* Step 3: Parse MiniMessage */
        return miniMessage.deserialize(processed, resolverBuilder.build());
    }

    /**
     * Convenience overload — parses without custom placeholders.
     */
    public Component parse(CommandSender sender, String rawMessage) {
        return parse(sender, rawMessage, Map.of());
    }

    /**
     * Parses a raw string with no player context (e.g., console output).
     */
    public Component parse(String rawMessage) {
        return miniMessage.deserialize(convertLegacyMiniMessageCodes(rawMessage));
    }

    private String convertLegacyMiniMessageCodes(String rawMessage) {
        if (rawMessage == null || !rawMessage.contains("&")) {
            return rawMessage;
        }

        StringBuilder result = new StringBuilder(rawMessage.length());
        for (int i = 0; i < rawMessage.length(); i++) {
            char current = rawMessage.charAt(i);
            if (current == '&' && i + 1 < rawMessage.length()) {
                char code = Character.toLowerCase(rawMessage.charAt(i + 1));
                String replacement = LEGACY_CODE_TO_MINI_MESSAGE.get(code);
                if (replacement != null) {
                    result.append(replacement);
                    i++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    // -------------------------------------------------------------------------
    // Message Delivery
    // -------------------------------------------------------------------------

    /**
     * Sends a message loaded from {@code messages.yml} to a CommandSender.
     *
     * @param sender       the recipient
     * @param messageKey   dot-notation key in messages.yml (e.g. "home.set")
     * @param placeholders custom MiniMessage tag replacements
     */
    public void send(CommandSender sender,
                     String messageKey,
                     Map<String, String> placeholders) {
        String raw = plugin.getConfigManager().getMessage(messageKey);
        if (raw == null || raw.isBlank()) return;

        Component component = parse(sender, raw, placeholders);
        ((Audience) sender).sendMessage(component);
    }

    /**
     * Convenience overload — sends without custom placeholders.
     */
    public void send(CommandSender sender, String messageKey) {
        send(sender, messageKey, Map.of());
    }

    /**
     * Sends a raw MiniMessage string (already resolved) directly to a sender.
     */
    public void sendRaw(CommandSender sender, String miniMessageString) {
        Component component = parse(sender, miniMessageString, Map.of());
        ((Audience) sender).sendMessage(component);
    }

    // -------------------------------------------------------------------------
    // ActionBar
    // -------------------------------------------------------------------------

    /**
     * Sends an ActionBar message to a player (used for warmup countdowns).
     *
     * @param player     the target player
     * @param messageKey key in messages.yml
     * @param placeholders custom tag replacements
     */
    public void sendActionBar(Player player,
                              String messageKey,
                              Map<String, String> placeholders) {
        String raw = plugin.getConfigManager().getMessage(messageKey);
        if (raw == null || raw.isBlank()) return;

        Component component = parse(player, raw, placeholders);
        player.sendActionBar(component);
    }

    /**
     * Sends a raw MiniMessage string as an ActionBar directly.
     */
    public void sendActionBarRaw(Player player, String miniMessageString) {
        Component component = parse(player, miniMessageString, Map.of());
        player.sendActionBar(component);
    }

    // -------------------------------------------------------------------------
    // Title & Subtitle
    // -------------------------------------------------------------------------

    /**
     * Sends a Title + Subtitle pair to a player.
     *
     * @param player        the target player
     * @param titleKey      messages.yml key for the title line
     * @param subtitleKey   messages.yml key for the subtitle line
     * @param fadeIn        fade-in duration in ticks
     * @param stay          stay duration in ticks
     * @param fadeOut       fade-out duration in ticks
     * @param placeholders  custom tag replacements applied to both lines
     */
    public void sendTitle(Player player,
                          String titleKey,
                          String subtitleKey,
                          int fadeIn,
                          int stay,
                          int fadeOut,
                          Map<String, String> placeholders) {
        String rawTitle    = plugin.getConfigManager().getMessage(titleKey);
        String rawSubtitle = plugin.getConfigManager().getMessage(subtitleKey);

        Component titleComponent    = parse(player, rawTitle    != null ? rawTitle    : "", placeholders);
        Component subtitleComponent = parse(player, rawSubtitle != null ? rawSubtitle : "", placeholders);

        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeIn  * 50L),
                Duration.ofMillis(stay    * 50L),
                Duration.ofMillis(fadeOut * 50L)
        );
        player.showTitle(Title.title(titleComponent, subtitleComponent, times));
    }

    // -------------------------------------------------------------------------
    // Warmup ActionBar Progress Bar
    // -------------------------------------------------------------------------

    /**
     * Builds and sends an ActionBar progress bar for the teleport warmup.
     *
     * <p>Example output: {@code [████████░░] 2s}</p>
     *
     * @param player        the target player
     * @param secondsLeft   remaining seconds
     * @param totalSeconds  total warmup seconds (for percentage calc)
     */
    public void sendWarmupBar(Player player, int secondsLeft, int totalSeconds) {
        int    barLength  = 20;
        double progress   = (double) (totalSeconds - secondsLeft) / totalSeconds;
        int    filled     = (int) (progress * barLength);
        int    empty      = barLength - filled;

        String bar = "<gradient:#00fbff:#0072ff>"
                + "█".repeat(Math.max(0, filled))
                + "</gradient><gray>"
                + "░".repeat(Math.max(0, empty))
                + "</gray>";

        String message = "<gray>Teleporting in </gray>"
                + "<gradient:#00fbff:#0072ff><bold>" + secondsLeft + "s</bold></gradient>"
                + " <dark_gray>[</dark_gray>" + bar + "<dark_gray>]</dark_gray>";

        sendActionBarRaw(player, message);
    }
}
