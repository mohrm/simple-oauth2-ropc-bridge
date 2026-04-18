package io.github.mohrm.simple_oauth2_ropc_bridge;

import java.time.Instant;

/**
 * Immutable token container used for lock-free reads from {@code AtomicReference}.
 */
public record TokenRecord(String accessToken, String refreshToken, Instant expiresAt) {

    public boolean isUsable(Instant now, int requiredRemainingSeconds) {
        return accessToken != null
                && !accessToken.isBlank()
                && expiresAt != null
                && now.plusSeconds(requiredRemainingSeconds).isBefore(expiresAt);
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }
}
