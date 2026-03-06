package com.worldboss.economy;

import java.util.UUID;

public record EconomyAccount(UUID playerId, String playerName, long balance) {
}
