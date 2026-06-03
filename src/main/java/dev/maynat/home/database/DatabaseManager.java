package dev.maynat.home.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.maynat.home.App;
import dev.maynat.home.model.Home;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.*;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * DatabaseManager — Async-first data access layer.
 *
 * <p>All public methods return {@link CompletableFuture} and execute their SQL
 * on a dedicated I/O thread supplied by HikariCP, keeping the main server
 * thread completely free.</p>
 *
 * <p>Supports SQLite (file-based, no server needed) and MySQL/MariaDB
 * (remote, production-grade). Selection is driven by {@code config.yml}.</p>
 */
public final class DatabaseManager {

    private final App plugin;
    private HikariDataSource dataSource;

    /* ── SQL Statements ── */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS maynat_homes (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid VARCHAR(36)  NOT NULL,
                name        VARCHAR(64)  NOT NULL,
                world       VARCHAR(128) NOT NULL,
                x           DOUBLE       NOT NULL,
                y           DOUBLE       NOT NULL,
                z           DOUBLE       NOT NULL,
                yaw         FLOAT        NOT NULL DEFAULT 0.0,
                pitch       FLOAT        NOT NULL DEFAULT 0.0,
                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (player_uuid, name)
            )
            """;

    private static final String CREATE_TABLE_MYSQL = """
            CREATE TABLE IF NOT EXISTS maynat_homes (
                id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                player_uuid VARCHAR(36)  NOT NULL,
                name        VARCHAR(64)  NOT NULL,
                world       VARCHAR(128) NOT NULL,
                x           DOUBLE       NOT NULL,
                y           DOUBLE       NOT NULL,
                z           DOUBLE       NOT NULL,
                yaw         FLOAT        NOT NULL DEFAULT 0.0,
                pitch       FLOAT        NOT NULL DEFAULT 0.0,
                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uq_player_home (player_uuid, name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String UPSERT_HOME_SQLITE =
            "INSERT INTO maynat_homes (player_uuid, name, world, x, y, z, yaw, pitch) " +
            "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid, name) DO UPDATE SET " +
            "world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, " +
            "yaw=excluded.yaw, pitch=excluded.pitch";

    private static final String UPSERT_HOME_MYSQL =
            "INSERT INTO maynat_homes (player_uuid, name, world, x, y, z, yaw, pitch) " +
            "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
            "world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), " +
            "yaw=VALUES(yaw), pitch=VALUES(pitch)";

    private static final String SELECT_HOMES =
            "SELECT name, world, x, y, z, yaw, pitch FROM maynat_homes " +
            "WHERE player_uuid = ? ORDER BY created_at ASC";

    private static final String SELECT_HOME =
            "SELECT world, x, y, z, yaw, pitch FROM maynat_homes " +
            "WHERE player_uuid = ? AND name = ?";

    private static final String DELETE_HOME =
            "DELETE FROM maynat_homes WHERE player_uuid = ? AND name = ?";

    private static final String RENAME_HOME =
            "UPDATE maynat_homes SET name = ? WHERE player_uuid = ? AND name = ?";

    private static final String DELETE_ALL_HOMES =
            "DELETE FROM maynat_homes WHERE player_uuid = ?";

    private static final String COUNT_HOMES =
            "SELECT COUNT(*) FROM maynat_homes WHERE player_uuid = ?";

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    public DatabaseManager(App plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes the HikariCP connection pool and creates tables.
     * Called synchronously on enable — the initial connect is acceptable
     * as it only happens once during startup.
     *
     * @return true if initialization succeeded
     */
    public boolean initialize() {
        try {
            HikariConfig hikariConfig = buildHikariConfig();
            dataSource = new HikariDataSource(hikariConfig);

            /* Run DDL synchronously at startup */
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(isMysql() ? CREATE_TABLE_MYSQL : CREATE_TABLE);
            }

            plugin.getLogger().info(String.format(
                    "Database connected (%s) — pool size: %d",
                    isMysql() ? "MySQL" : "SQLite",
                    hikariConfig.getMaximumPoolSize()));
            return true;

        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to initialize database", ex);
            return false;
        }
    }

    /**
     * Builds a {@link HikariConfig} from the plugin's config.yml values.
     */
    private HikariConfig buildHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        var config = plugin.getConfigManager();

        if (isMysql()) {
            String host     = config.getString("database.mysql.host",     "localhost");
            int    port     = config.getInt   ("database.mysql.port",     3306);
            String database = config.getString("database.mysql.database", "maynat_home");
            String user     = config.getString("database.mysql.user",     "root");
            String password = config.getString("database.mysql.password", "");
            boolean ssl     = config.getBoolean("database.mysql.ssl",     false);

            cfg.setJdbcUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true" +
                    "&characterEncoding=utf8&autoReconnect=true",
                    host, port, database, ssl));
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
            cfg.setMaximumPoolSize(config.getInt("database.mysql.pool-size", 10));

        } else {
            String path = plugin.getDataFolder().getAbsolutePath() + "/data.db";
            cfg.setJdbcUrl("jdbc:sqlite:" + path);
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1); // SQLite is single-writer
        }

        /* Universal HikariCP tuning for low-latency Minecraft environments */
        cfg.setPoolName("MaynatHome-Pool");
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(300_000);
        cfg.setMaxLifetime(600_000);
        cfg.setLeakDetectionThreshold(15_000);

        /* Performance optimizations */
        cfg.addDataSourceProperty("cachePrepStmts",          "true");
        cfg.addDataSourceProperty("prepStmtCacheSize",       "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit",   "2048");

        return cfg;
    }

    // -------------------------------------------------------------------------
    // Async Public API
    // -------------------------------------------------------------------------

    /**
     * Saves (or updates) a home for the given player asynchronously.
     *
     * @param playerUuid the player's UUID as string
     * @param homeName   the home name (case-insensitive, stored as given)
     * @param location   the home location
     * @return a future that resolves to true on success
     */
    public CompletableFuture<Boolean> saveHome(String playerUuid,
                                               String homeName,
                                               Location location) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = isMysql() ? UPSERT_HOME_MYSQL : UPSERT_HOME_SQLITE;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, playerUuid);
                ps.setString(2, homeName.toLowerCase());
                ps.setString(3, Objects.requireNonNull(location.getWorld()).getName());
                ps.setDouble(4, location.getX());
                ps.setDouble(5, location.getY());
                ps.setDouble(6, location.getZ());
                ps.setFloat (7, location.getYaw());
                ps.setFloat (8, location.getPitch());
                ps.executeUpdate();
                return true;

            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to save home '" + homeName + "' for " + playerUuid, ex);
                return false;
            }
        });
    }

    /**
     * Retrieves all homes for a player asynchronously.
     *
     * @param playerUuid the player's UUID as string
     * @return a future resolving to an unmodifiable map of homeName → Home
     */
    public CompletableFuture<Map<String, Home>> getHomes(String playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Home> homes = new LinkedHashMap<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SELECT_HOMES)) {

                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name  = rs.getString("name");
                        String world = rs.getString("world");
                        double x     = rs.getDouble("x");
                        double y     = rs.getDouble("y");
                        double z     = rs.getDouble("z");
                        float  yaw   = rs.getFloat("yaw");
                        float  pitch = rs.getFloat("pitch");

                        World bWorld = Bukkit.getWorld(world);
                        if (bWorld == null) {
                            plugin.getLogger().warning(
                                    "World '" + world + "' not loaded — skipping home '" + name + "'");
                            continue;
                        }
                        Location loc = new Location(bWorld, x, y, z, yaw, pitch);
                        homes.put(name, new Home(playerUuid, name, loc));
                    }
                }

            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to load homes for " + playerUuid, ex);
            }
            return Collections.unmodifiableMap(homes);
        });
    }

    /**
     * Retrieves a single home by name for a player asynchronously.
     *
     * @param playerUuid the player's UUID as string
     * @param homeName   the home name
     * @return a future resolving to an Optional containing the Home, or empty
     */
    public CompletableFuture<Optional<Home>> getHome(String playerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SELECT_HOME)) {

                ps.setString(1, playerUuid);
                ps.setString(2, homeName.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String world = rs.getString("world");
                        double x     = rs.getDouble("x");
                        double y     = rs.getDouble("y");
                        double z     = rs.getDouble("z");
                        float  yaw   = rs.getFloat("yaw");
                        float  pitch = rs.getFloat("pitch");

                        World bWorld = Bukkit.getWorld(world);
                        if (bWorld == null) {
                            plugin.getLogger().warning(
                                    "World '" + world + "' is not loaded!");
                            return Optional.empty();
                        }
                        Location loc = new Location(bWorld, x, y, z, yaw, pitch);
                        return Optional.of(new Home(playerUuid, homeName.toLowerCase(), loc));
                    }
                }

            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to retrieve home '" + homeName + "' for " + playerUuid, ex);
            }
            return Optional.empty();
        });
    }

    /**
     * Deletes a specific home for a player asynchronously.
     *
     * @param playerUuid the player's UUID as string
     * @param homeName   the home name to delete
     * @return a future resolving to true if a row was deleted
     */
    public CompletableFuture<Boolean> deleteHome(String playerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(DELETE_HOME)) {

                ps.setString(1, playerUuid);
                ps.setString(2, homeName.toLowerCase());
                return ps.executeUpdate() > 0;

            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to delete home '" + homeName + "' for " + playerUuid, ex);
                return false;
            }
        });
    }

    public CompletableFuture<RenameResult> renameHome(String playerUuid, String oldName, String newName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(RENAME_HOME)) {

                ps.setString(1, newName.toLowerCase());
                ps.setString(2, playerUuid);
                ps.setString(3, oldName.toLowerCase());

                int updated = ps.executeUpdate();
                if (updated == 0) {
                    return new RenameResult(RenameStatus.NOT_FOUND);
                }
                return new RenameResult(RenameStatus.SUCCESS);

            } catch (SQLException ex) {
                if (ex instanceof SQLIntegrityConstraintViolationException
                        || ex.getErrorCode() == 19
                        || ex.getErrorCode() == 1062
                        || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("unique"))) {
                    return new RenameResult(RenameStatus.NAME_TAKEN);
                }
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to rename home '" + oldName + "' -> '" + newName + "' for " + playerUuid, ex);
                return new RenameResult(RenameStatus.ERROR);
            }
        });
    }

    /**
     * Deletes ALL homes for a player (e.g., on player data wipe).
     *
     * @param playerUuid the player's UUID as string
     * @return a future resolving to the number of rows deleted
     */
    public CompletableFuture<Integer> deleteAllHomes(String playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(DELETE_ALL_HOMES)) {

                ps.setString(1, playerUuid);
                return ps.executeUpdate();

            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to delete all homes for " + playerUuid, ex);
                return 0;
            }
        });
    }

    /**
     * Counts the number of homes a player currently has.
     *
     * @param playerUuid the player's UUID as string
     * @return a future resolving to the home count
     */
    public CompletableFuture<Integer> countHomes(String playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(COUNT_HOMES)) {

                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }

            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to count homes for " + playerUuid, ex);
            }
            return 0;
        });
    }

    // -------------------------------------------------------------------------
    // Rename result types
    // -------------------------------------------------------------------------

    public enum RenameStatus {
        SUCCESS,
        NOT_FOUND,
        NAME_TAKEN,
        ERROR
    }

    public static final class RenameResult {
        private final RenameStatus status;

        public RenameResult(RenameStatus status) {
            this.status = status;
        }

        public RenameStatus getStatus() {
            return status;
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Closes the HikariCP data source gracefully.
     * Must be called from {@link App#onDisable()}.
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool closed.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isMysql() {
        return plugin.getConfigManager()
                .getString("database.type", "sqlite")
                .equalsIgnoreCase("mysql");
    }
}
