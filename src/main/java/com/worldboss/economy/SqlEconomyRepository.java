package com.worldboss.economy;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;

public class SqlEconomyRepository implements EconomyRepository {
    private final JavaPlugin plugin;
    private final DatabaseSettings settings;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;

    public SqlEconomyRepository(JavaPlugin plugin, DatabaseSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        switch (settings.type()) {
            case "mysql" -> {
                this.jdbcUrl = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database() +
                        "?useSSL=" + settings.useSsl() + "&allowPublicKeyRetrieval=true&characterEncoding=utf8";
                this.jdbcUser = settings.username();
                this.jdbcPassword = settings.password();
            }
            case "sqlite" -> {
                File dbFile = new File(plugin.getDataFolder(), settings.sqliteFile());
                this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                this.jdbcUser = "";
                this.jdbcPassword = "";
            }
            default -> throw new IllegalArgumentException("Unsupported database.type: " + settings.type());
        }
    }

    @Override
    public void initialize() throws SQLException {
        if ("sqlite".equals(settings.type())) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite driver not found", e);
            }
        }
        if ("mysql".equals(settings.type())) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                throw new SQLException("MySQL driver not found", e);
            }
        }

        String ddl = """
                CREATE TABLE IF NOT EXISTS wb_economy_accounts (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(16) NOT NULL,
                    balance BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;
        String index = "CREATE INDEX IF NOT EXISTS idx_wb_economy_accounts_name ON wb_economy_accounts(player_name)";

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            statement.execute(index);
        }
    }

    @Override
    public long getBalance(UUID playerId, String playerName) throws SQLException {
        ensureAccount(playerId, playerName);
        String sql = "SELECT balance FROM wb_economy_accounts WHERE player_uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getLong("balance");
            }
        }
    }

    @Override
    public Optional<EconomyAccount> findByName(String playerName) throws SQLException {
        String sql = "SELECT player_uuid, player_name, balance FROM wb_economy_accounts WHERE LOWER(player_name) = LOWER(?) LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                UUID id = UUID.fromString(rs.getString("player_uuid"));
                return Optional.of(new EconomyAccount(id, rs.getString("player_name"), rs.getLong("balance")));
            }
        }
    }

    @Override
    public void addBalance(UUID playerId, String playerName, long amount) throws SQLException {
        if (amount == 0) return;
        ensureAccount(playerId, playerName);
        String sql = "UPDATE wb_economy_accounts SET balance = balance + ?, player_name = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setString(2, playerName);
            ps.setString(3, playerId.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean transfer(UUID fromId, String fromName, UUID toId, String toName, long amount) throws SQLException {
        if (amount <= 0) return false;
        ensureAccount(fromId, fromName);
        ensureAccount(toId, toName);

        String takeSql = "UPDATE wb_economy_accounts SET balance = balance - ?, player_name = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE player_uuid = ? AND balance >= ?";
        String giveSql = "UPDATE wb_economy_accounts SET balance = balance + ?, player_name = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?";

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement take = connection.prepareStatement(takeSql);
                 PreparedStatement give = connection.prepareStatement(giveSql)) {
                take.setLong(1, amount);
                take.setString(2, fromName);
                take.setString(3, fromId.toString());
                take.setLong(4, amount);
                int debited = take.executeUpdate();
                if (debited != 1) {
                    connection.rollback();
                    return false;
                }

                give.setLong(1, amount);
                give.setString(2, toName);
                give.setString(3, toId.toString());
                give.executeUpdate();

                connection.commit();
                return true;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void ensureAccount(UUID playerId, String playerName) throws SQLException {
        String insert = "INSERT INTO wb_economy_accounts(player_uuid, player_name, balance) VALUES (?, ?, 0)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerName);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!isDuplicate(e)) {
                throw e;
            }
            String updateName = "UPDATE wb_economy_accounts SET player_name = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?";
            try (Connection connection = openConnection();
                 PreparedStatement ps = connection.prepareStatement(updateName)) {
                ps.setString(1, playerName);
                ps.setString(2, playerId.toString());
                ps.executeUpdate();
            }
        }
    }

    private boolean isDuplicate(SQLException e) {
        int code = e.getErrorCode();
        String sqlState = e.getSQLState();
        return code == 19 || code == 1062 || "23000".equals(sqlState) || "23505".equals(sqlState);
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
    }

    @Override
    public void close() {
        plugin.getLogger().info("Economy repository stopped");
    }
}
