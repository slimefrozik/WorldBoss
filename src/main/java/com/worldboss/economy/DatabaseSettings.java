package com.worldboss.economy;

import org.bukkit.configuration.ConfigurationSection;

public record DatabaseSettings(
        String type,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl
) {
    public static DatabaseSettings fromConfig(ConfigurationSection section) {
        if (section == null) {
            return new DatabaseSettings("sqlite", "economy.db", "localhost", 3306, "worldboss", "root", "", false);
        }
        return new DatabaseSettings(
                section.getString("type", "sqlite").toLowerCase(),
                section.getString("sqlite.file", "economy.db"),
                section.getString("mysql.host", "localhost"),
                section.getInt("mysql.port", 3306),
                section.getString("mysql.database", "worldboss"),
                section.getString("mysql.username", "root"),
                section.getString("mysql.password", ""),
                section.getBoolean("mysql.ssl", false)
        );
    }
}
