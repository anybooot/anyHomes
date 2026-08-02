package dev.anyhomes.gui;

import dev.anyhomes.App;
import dev.anyhomes.model.Home;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.*;
import java.util.logging.Level;
import java.util.WeakHashMap;

/**
 * MenuManager — YAML-driven inventory GUI system.
 *
 * <p>Menus are defined in {@code plugins/Maynat-Home/menus/} as individual
 * YAML files. Each file describes:</p>
 * <ul>
 *   <li>Title (MiniMessage format)</li>
 *   <li>Size (rows × 9)</li>
 *   <li>Filler items for borders</li>
 *   <li>Static items with click-actions</li>
 *   <li>Dynamic slots for home entries</li>
 * </ul>
 */
public final class MenuManager {

    private final App plugin;

    /* Loaded menu configurations keyed by file name (without extension) */
    private final Map<String, YamlConfiguration> menus = new HashMap<>();

    private final Map<Inventory, MenuState> openMenus = Collections.synchronizedMap(new WeakHashMap<>());

    private File menusFolder;

    public MenuManager(App plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Scans the {@code menus/} folder and loads every {@code .yml} file.
     */
    public void loadMenus() {
        menus.clear();

        menusFolder = new File(plugin.getDataFolder(), "menus");
        if (!menusFolder.exists()) {
            menusFolder.mkdirs();
        }

        /* Ensure bundled default menu files exist locally. */
        saveDefaultMenu("homes.yml");
        saveDefaultMenu("confirm-delete.yml");
        saveDefaultMenu("help.yml");

        File[] files = menusFolder.listFiles(
                (dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            String key = file.getName().replace(".yml", "").toLowerCase();
            menus.put(key, YamlConfiguration.loadConfiguration(file));
            plugin.getLogger().info("Loaded menu: " + key);
        }
    }

    private void saveDefaultMenu(String name) {
        File target = new File(menusFolder, name);
        if (!target.exists()) {
            plugin.saveResource("menus/" + name, false);
        }
    }

    // -------------------------------------------------------------------------
    // Homes List GUI
    // -------------------------------------------------------------------------

    /**
     * Opens the homes-list GUI for a player, populated with their saved homes.
     *
     * @param player the target player
     * @param homes  the player's home map (name → Home)
     */
    public void openHomesMenu(Player player, Map<String, Home> homes) {
        openHomesMenu(player, homes, -1, 1);
    }

    public void openHomesMenu(Player player, Map<String, Home> homes, int maxHomes) {
        openHomesMenu(player, homes, maxHomes, 1);
    }

    /**
     * Opens the homes menu with pagination support.
     * @param player the target player
     * @param homes the player's home map (name → Home)
     * @param maxHomes the maximum number of homes allowed (-1 for unlimited)
     * @param pageNumber the page to display (1-based)
     */
    public void openHomesMenu(Player player, Map<String, Home> homes, int maxHomes, int pageNumber) {
        YamlConfiguration cfg = menus.get("homes");
        if (cfg == null) {
            /* Fallback: text list */
            plugin.getMessageUtils().send(player, "home.list-header",
                    Map.of("count", String.valueOf(homes.size())));
            homes.values().forEach(h ->
                    plugin.getMessageUtils().send(player, "home.list-entry",
                            Map.of("home", h.displayName(),
                                   "world", h.worldName(),
                                   "coords", h.formattedCoords())));
            return;
        }

        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", 6)));
        int size = rows * 9;

        /* Get home slots from config or auto-generate */
        List<Integer> homeSlots = getHomeSlots(cfg, size);
        int slotsPerPage = homeSlots.size();
        
        if (slotsPerPage <= 0) {
            plugin.getLogger().warning("No home slots available in menu config");
            return;
        }

        /* Calculate pagination */
        List<Home> homeList = new ArrayList<>(homes.values());
        int totalPages = (int) Math.ceil((double) homeList.size() / slotsPerPage);
        totalPages = Math.max(1, totalPages);
        
        /* Clamp page number */
        pageNumber = Math.max(1, Math.min(pageNumber, totalPages));

        /* Calculate which homes to display on this page */
        int startIdx = (pageNumber - 1) * slotsPerPage;
        int endIdx = Math.min(startIdx + slotsPerPage, homeList.size());
        List<Home> pageHomes = homeList.subList(startIdx, endIdx);

        /* Build title with page indicator */
        String rawTitle = cfg.getString("title",
                "<gradient:#00fbff:#0072ff><bold>Your Homes</bold></gradient>");
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("count", String.valueOf(homes.size()));
        placeholders.put("max", maxHomes < 0 ? "∞" : String.valueOf(maxHomes));
        placeholders.put("page", String.valueOf(pageNumber));
        placeholders.put("maxpage", String.valueOf(totalPages));
        
        /* Add page indicator if pagination is enabled and there are multiple pages */
        boolean paginationEnabled = plugin.getConfigManager().getBoolean("gui.homes-menu.pagination-enabled", true);
        boolean showPageIndicator = plugin.getConfigManager().getBoolean("gui.homes-menu.show-page-indicator", true);
        
        if (paginationEnabled && totalPages > 1 && showPageIndicator) {
            String pageFormat = plugin.getConfigManager().getString("gui.homes-menu.page-indicator-format", " <page>/<max>");
            String pageIndicator = pageFormat
                    .replace("<page>", String.valueOf(pageNumber))
                    .replace("<max>", String.valueOf(totalPages));
            rawTitle = rawTitle + pageIndicator;
        }

        Component title = plugin.getMessageUtils().parse(player, rawTitle, placeholders);
        Inventory inv = Bukkit.createInventory(null, size, title);

        /* Fill borders */
        applyFiller(inv, cfg, size);

        /* Place static items */
        ConfigurationSection items = cfg.getConfigurationSection("items");
        Map<Integer, String> staticActions = new HashMap<>();
        if (items != null) {
            for (String itemKey : items.getKeys(false)) {
                ConfigurationSection itemSection = items.getConfigurationSection(itemKey);
                if (itemSection == null) continue;
                
                int slot = itemSection.getInt("slot", -1);
                if (slot < 0 || slot >= size) continue;
                
                String action = itemSection.getString("action", "NONE").toUpperCase(Locale.ROOT);
                
                /* Skip pagination arrows if they're not needed */
                if ("PREVIOUS_PAGE".equals(action) && (totalPages <= 1 || pageNumber <= 1)) {
                    continue;
                }
                if ("NEXT_PAGE".equals(action) && (totalPages <= 1 || pageNumber >= totalPages)) {
                    continue;
                }
                
                staticActions.put(slot, action);
                inv.setItem(slot, buildItem(player, itemSection, placeholders));
            }
        }

        /* Place home entries for this page */
        Map<Integer, String> homeSlotMapping = new HashMap<>();
        
        for (int i = 0; i < pageHomes.size(); i++) {
            int slotIndex = homeSlots.get(i);
            if (slotIndex < 0 || slotIndex >= size) continue;
            
            Home home = pageHomes.get(i);
            inv.setItem(slotIndex, buildHomeItem(player, home, cfg));
            homeSlotMapping.put(slotIndex, home.name());
        }

        /* Store pagination info in context */
        Map<String, String> context = new HashMap<>();
        context.put("page", String.valueOf(pageNumber));
        context.put("maxpage", String.valueOf(totalPages));

        openMenus.put(inv, new MenuState("homes", homeSlotMapping, staticActions, context, pageNumber, totalPages));
        player.openInventory(inv);
    }

    /**
     * Gets the list of slots where homes can be placed from the config.
     * If home-slots is empty, auto-generates them starting from homes-start-slot.
     */
    private List<Integer> getHomeSlots(YamlConfiguration cfg, int inventorySize) {
        List<Integer> slots = new ArrayList<>(cfg.getIntegerList("home-slots"));
        
        if (!slots.isEmpty()) {
            /* Filter out invalid slots */
            slots.removeIf(slot -> slot < 0 || slot >= inventorySize);
            return slots;
        }
        
        /* Auto-generate slots from homes-start-slot */
        int startSlot = cfg.getInt("homes-start-slot", 10);
        int slot = startSlot;
        
        while (slot < inventorySize) {
            /* Skip border columns (slots ending in 8 or 0) */
            if ((slot % 9) == 8) {
                slot += 2;
                continue;
            }
            slots.add(slot);
            slot++;
        }
        
        return slots;
    }

    // -------------------------------------------------------------------------
    // Item Builders
    // -------------------------------------------------------------------------

    /**
     * Builds a generic ItemStack from a YAML section.
     * Supports Base64 player heads, custom display names, and lore.
     */
    public ItemStack buildItem(Player viewer, ConfigurationSection section) {
        return buildItem(viewer, section, Map.of());
    }

    public ItemStack buildItem(Player viewer, ConfigurationSection section,
                               Map<String, String> placeholders) {
        String materialName = section.getString("material", "STONE").toUpperCase();
        String base64       = section.getString("base64", null);
        if (base64 == null || base64.isBlank()) {
            base64 = section.getString("base64-head", null);
        }
        String displayName  = section.getString("display-name", "");
        List<String> lore   = section.getStringList("lore");
        String displayMode  = section.getString("display-mode", "BOTH").toUpperCase(Locale.ROOT);
        boolean hideDetails = section.getBoolean("hide-details", false);
        boolean hideTooltip = section.getBoolean("hide_tooltip", false);
        Set<ItemFlag> itemFlags = parseItemFlags(section);

        ItemStack item;

        if (base64 != null && !base64.isBlank()) {
            item = buildBase64Head(base64);
        } else {
            Material mat = Material.matchMaterial(materialName);
            item = new ItemStack(mat != null ? mat : Material.STONE);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (hideTooltip) {
            meta.setHideTooltip(true);
        } else if (hideDetails) {
            meta.displayName(Component.empty());
            meta.lore(Collections.emptyList());
        } else {
            boolean showTitle = !displayMode.equals("MESSAGE");
            boolean showMessage = !displayMode.equals("TITLE");

            if (showTitle && !displayName.isBlank()) {
                meta.displayName(normalizeComponentItalics(
                        plugin.getMessageUtils().parse(viewer, displayName, placeholders)));
            } else if (!showTitle) {
                meta.displayName(Component.empty());
            }

            if (showMessage && !lore.isEmpty()) {
                List<Component> loreComponents = new ArrayList<>();
                for (String line : lore) {
                    loreComponents.add(normalizeComponentItalics(
                            plugin.getMessageUtils().parse(viewer, line, placeholders)));
                }
                meta.lore(loreComponents);
            }
        }

        if (!hideTooltip) {
            itemFlags.add(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (!itemFlags.isEmpty()) {
                meta.addItemFlags(itemFlags.toArray(new ItemFlag[0]));
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Builds a home-specific item from the YAML template in {@code homes.yml}.
     */
    private ItemStack buildHomeItem(Player viewer, Home home, YamlConfiguration cfg) {
        ConfigurationSection template = cfg.getConfigurationSection("home-item");

        String material    = template != null ? template.getString("material", "BED") : "BED";
        String base64      = template != null ? template.getString("base64", null) : null;
        if (base64 == null || base64.isBlank()) {
            base64 = template != null ? template.getString("base64-head", null) : null;
        }
        String displayName = template != null
                ? template.getString("display-name", "<white><home>")
                : "<white><home>";
        List<String> lore  = template != null ? template.getStringList("lore") : List.of();
        String displayMode = template != null ? template.getString("display-mode", "BOTH") : "BOTH";
        boolean hideDetails = template != null && template.getBoolean("hide-details", false);
        boolean hideTooltip = template != null && template.getBoolean("hide_tooltip", false);

        Map<String, String> ph = Map.of(
                "home",   home.displayName(),
                "world",  home.worldName(),
                "coords", home.formattedCoords()
        );

        ItemStack item;
        if (base64 != null && !base64.isBlank()) {
            item = buildBase64Head(base64);
        } else {
            item = new ItemStack(
                    Objects.requireNonNullElse(Material.matchMaterial(material.toUpperCase()),
                            Material.RED_BED));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Set<ItemFlag> itemFlags = parseItemFlags(template);
        if (hideTooltip) {
            meta.setHideTooltip(true);
        } else if (hideDetails) {
            meta.displayName(Component.empty());
            meta.lore(Collections.emptyList());
        } else {
            boolean showTitle = !displayMode.equalsIgnoreCase("MESSAGE");
            boolean showMessage = !displayMode.equalsIgnoreCase("TITLE");

            if (showTitle) {
                meta.displayName(normalizeComponentItalics(
                        plugin.getMessageUtils().parse(viewer, displayName, ph)));
            } else {
                meta.displayName(Component.empty());
            }

            if (showMessage && !lore.isEmpty()) {
                List<Component> loreComponents = new ArrayList<>();
                for (String line : lore) {
                    loreComponents.add(normalizeComponentItalics(
                            plugin.getMessageUtils().parse(viewer, line, ph)));
                }
                meta.lore(loreComponents);
            }
        }

        if (!hideTooltip) {
            itemFlags.add(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (!itemFlags.isEmpty()) {
                meta.addItemFlags(itemFlags.toArray(new ItemFlag[0]));
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private Set<ItemFlag> parseItemFlags(ConfigurationSection section) {
        Set<ItemFlag> result = EnumSet.noneOf(ItemFlag.class);
        if (section == null) {
            return result;
        }

        List<String> flags = section.getStringList("item_flags");
        if (flags.isEmpty()) {
            flags = section.getStringList("item-flags");
        }

        for (String flag : flags) {
            if (flag == null || flag.isBlank()) continue;
            try {
                result.add(ItemFlag.valueOf(flag.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Unknown item flag in menu config: " + flag);
            }
        }
        return result;
    }

    /**
     * Creates a player skull using a Base64 texture string.
     * Compatible with Paper 1.21+ skull API.
     */
    private ItemStack buildBase64Head(String base64) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta  = (SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;

        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            String url = new String(Base64.getDecoder().decode(base64));
            /* base64 decodes to: {"textures":{"SKIN":{"url":"..."}}} */
            int start = url.indexOf("http");
            int end   = url.indexOf('"', start);
            if (start >= 0 && end > start) {
                String textureUrl = normalizeSkinUrl(url.substring(start, end));
                textures.setSkin(URI.create(textureUrl).toURL());
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to apply Base64 skull texture", ex);
        }

        skull.setItemMeta(meta);
        return skull;
    }

    private Component normalizeComponentItalics(Component component) {
        if (component == null) {
            return Component.empty();
        }

        Component normalized = component;
        if (!component.style().hasDecoration(TextDecoration.ITALIC)) {
            normalized = component.decoration(TextDecoration.ITALIC, false);
        }

        if (!normalized.children().isEmpty()) {
            List<Component> children = new ArrayList<>();
            for (Component child : normalized.children()) {
                children.add(normalizeComponentItalics(child));
            }
            normalized = normalized.children(children);
        }
        return normalized;
    }

    private String normalizeSkinUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            if ("textures.minecraf.net".equalsIgnoreCase(uri.getHost())) {
                uri = new URI(uri.getScheme(), uri.getUserInfo(), "textures.minecraft.net",
                        uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
                return uri.toString();
            }
        } catch (Exception ignored) {
            // fallback to original url
        }
        return url;
    }

    // -------------------------------------------------------------------------
    // Filler
    // -------------------------------------------------------------------------

    /**
     * Fills border slots with the configured filler item.
     */
    private void applyFiller(Inventory inv, YamlConfiguration cfg, int size) {
        ConfigurationSection filler = cfg.getConfigurationSection("filler");
        if (filler == null || !filler.getBoolean("enabled", false)) return;

        String matName = filler.getString("material", "GRAY_STAINED_GLASS_PANE").toUpperCase();
        boolean hideDetails = filler.getBoolean("hide-details", true);
        Set<ItemFlag> itemFlags = parseItemFlags(filler);
        Material mat   = Objects.requireNonNullElse(
                Material.matchMaterial(matName), Material.GRAY_STAINED_GLASS_PANE);

        ItemStack glass = new ItemStack(mat);
        ItemMeta  meta  = glass.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            if (hideDetails) {
                itemFlags.add(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            }
            if (!itemFlags.isEmpty()) {
                meta.addItemFlags(itemFlags.toArray(new ItemFlag[0]));
            }
            glass.setItemMeta(meta);
        }

        int rows = size / 9;
        for (int col = 0; col < 9; col++) {
            inv.setItem(col, glass.clone());              // top row
            inv.setItem(size - 9 + col, glass.clone());  // bottom row
        }
        for (int row = 1; row < rows - 1; row++) {
            inv.setItem(row * 9, glass.clone());          // left column
            inv.setItem(row * 9 + 8, glass.clone());      // right column
        }
    }

    public MenuState getMenuState(Inventory inventory) {
        return openMenus.get(inventory);
    }

    public void openConfirmDeleteMenu(Player player, String homeName) {
        String uuid = player.getUniqueId().toString();
        plugin.getDatabaseManager().getHome(uuid, homeName).thenAccept(optHome -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (optHome.isEmpty()) {
                    plugin.getMessageUtils().send(player, "home.not-found", Map.of("home", homeName));
                    return;
                }
                openConfirmDeleteMenu(player, optHome.get());
            });
        });
    }

    public void openConfirmDeleteMenu(Player player, Home home) {
        YamlConfiguration cfg = menus.get("confirm-delete");
        if (cfg == null) {
            player.closeInventory();
            plugin.getMessageUtils().send(player, "general.error");
            return;
        }

        String rawTitle = cfg.getString("title",
                "<gradient:#ff5f6d:#ffc371><bold>Confirm Deletion</bold></gradient>");
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", 3)));
        int size = rows * 9;

        Location loc = home.location();
        String coordsStr = String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ());
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";

        Map<String, String> placeholders = Map.of(
                "home", home.name(),
                "world", worldName,
                "coords", coordsStr
        );

        Component title = plugin.getMessageUtils().parse(player, rawTitle, placeholders);
        Inventory inv = Bukkit.createInventory(null, size, title);

        applyFiller(inv, cfg, size);

        ConfigurationSection items = cfg.getConfigurationSection("items");
        Map<Integer, String> staticActions = new HashMap<>();
        if (items != null) {
            for (String itemKey : items.getKeys(false)) {
                ConfigurationSection itemSection = items.getConfigurationSection(itemKey);
                if (itemSection == null) continue;
                int slot = itemSection.getInt("slot", -1);
                if (slot < 0 || slot >= size) continue;
                String action = itemSection.getString("action", "NONE").toUpperCase(Locale.ROOT);
                staticActions.put(slot, action);
                inv.setItem(slot, buildItem(player, itemSection, placeholders));
            }
        }

        openMenus.put(inv, new MenuState("confirm-delete", Map.of(), staticActions, placeholders));
        player.openInventory(inv);
    }

    public void openHelpMenu(Player player) {
        YamlConfiguration cfg = menus.get("help");
        if (cfg == null) {
            player.closeInventory();
            plugin.getMessageUtils().send(player, "general.error");
            return;
        }

        String rawTitle = cfg.getString("title",
                "<gradient:#a855f7:#06b6d4><bold>Help & Commands</bold></gradient>");
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", 4)));
        int size = rows * 9;

        Map<String, String> placeholders = Map.of();

        Component title = plugin.getMessageUtils().parse(player, rawTitle, placeholders);
        Inventory inv = Bukkit.createInventory(null, size, title);

        applyFiller(inv, cfg, size);

        ConfigurationSection items = cfg.getConfigurationSection("items");
        Map<Integer, String> staticActions = new HashMap<>();
        if (items != null) {
            for (String itemKey : items.getKeys(false)) {
                ConfigurationSection itemSection = items.getConfigurationSection(itemKey);
                if (itemSection == null) continue;
                int slot = itemSection.getInt("slot", -1);
                if (slot < 0 || slot >= size) continue;
                String action = itemSection.getString("action", "NONE").toUpperCase(Locale.ROOT);
                staticActions.put(slot, action);
                inv.setItem(slot, buildItem(player, itemSection, placeholders));
            }
        }

        openMenus.put(inv, new MenuState("help", Map.of(), staticActions, Map.of()));
        player.openInventory(inv);
    }

    public boolean isManagedMenu(Inventory inventory) {
        return openMenus.containsKey(inventory);
    }

    public void unregisterMenu(Inventory inventory) {
        openMenus.remove(inventory);
    }

    public static final class MenuState {
        private final String menuKey;
        private final Map<Integer, String> homeSlotMapping;
        private final Map<Integer, String> staticActions;
        private final Map<String, String> context;
        private final int currentPage;
        private final int totalPages;

        public MenuState(String menuKey,
                         Map<Integer, String> homeSlotMapping,
                         Map<Integer, String> staticActions,
                         Map<String, String> context) {
            this(menuKey, homeSlotMapping, staticActions, context, 1, 1);
        }

        public MenuState(String menuKey,
                         Map<Integer, String> homeSlotMapping,
                         Map<Integer, String> staticActions,
                         Map<String, String> context,
                         int currentPage,
                         int totalPages) {
            this.menuKey = menuKey;
            this.homeSlotMapping = homeSlotMapping;
            this.staticActions = staticActions;
            this.context = context;
            this.currentPage = currentPage;
            this.totalPages = totalPages;
        }

        public boolean isHomeSlot(int slot) {
            return homeSlotMapping.containsKey(slot);
        }

        public String getHomeName(int slot) {
            return homeSlotMapping.get(slot);
        }

        public String getStaticAction(int slot) {
            return staticActions.get(slot);
        }

        public String getContextValue(String key) {
            return context.get(key);
        }

        public String getMenuKey() {
            return menuKey;
        }

        public int getCurrentPage() {
            return currentPage;
        }

        public int getTotalPages() {
            return totalPages;
        }
    }

    public Map<String, YamlConfiguration> getMenus() {
        return Collections.unmodifiableMap(menus);
    }
}
