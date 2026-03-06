package com.worldboss.economy;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public interface EconomyRepository {
    void initialize() throws SQLException;

    long getBalance(UUID playerId, String playerName) throws SQLException;

    Optional<EconomyAccount> findByName(String playerName) throws SQLException;

    void addBalance(UUID playerId, String playerName, long amount) throws SQLException;

    boolean transfer(UUID fromId, String fromName, UUID toId, String toName, long amount) throws SQLException;

    void close();
}
