package io.github.mohrm.simple_oauth2_ropc_bridge;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free OAuth2 token provider with cached access tokens and refresh/ROPC fallback.
 */
public final class OAuth2TokenProvider {

    private static final System.Logger LOGGER = System.getLogger(OAuth2TokenProvider.class.getName());

    /**
     * We reserve 5 minutes of validity to avoid using a token that could expire during transport,
     * retries, or small clock drifts between this service and the IdP.
     */
    private static final long DEFAULT_CLOCK_SKEW_SECONDS = 300L;

    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(
            "\\\"\\s*access_token\\s*\\\"\\s*:\\s*\\\"\\s*((?:\\\\.|[^\\\"\\\\])+)\\s*\\\"");
    private static final Pattern REFRESH_TOKEN_PATTERN = Pattern.compile(
            "\\\"\\s*refresh_token\\s*\\\"\\s*:\\s*\\\"\\s*((?:\\\\.|[^\\\"\\\\])+)\\s*\\\"");
    private static final Pattern EXPIRES_IN_PATTERN = Pattern.compile(
            "\\\"\\s*expires_in\\s*\\\"\\s*:\\s*(\\d+)");

    private final HttpClient httpClient;
    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final String username;
    private final String password;
    private final long clockSkewSeconds;

    private final AtomicReference<TokenRecord> tokenState = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    public OAuth2TokenProvider(
            HttpClient httpClient,
            URI tokenEndpoint,
            String clientId,
            String clientSecret,
            String username,
            String password
    ) {
        this(httpClient, tokenEndpoint, clientId, clientSecret, username, password, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public OAuth2TokenProvider(
            HttpClient httpClient,
            URI tokenEndpoint,
            String clientId,
            String clientSecret,
            String username,
            String password,
            long clockSkewSeconds
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint must not be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret must not be null");
        this.username = Objects.requireNonNull(username, "username must not be null");
        this.password = Objects.requireNonNull(password, "password must not be null");
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String getAccessToken() {
        Instant now = Instant.now();
        TokenRecord cached = tokenState.get();
        if (isValid(cached, now)) {
            return cached.accessToken();
        }

        // Double-checked locking: allows lock-free fast-path for healthy caches while ensuring
        // only one caller performs remote token I/O when the token is stale/missing.
        refreshLock.lock();
        try {
            now = Instant.now();
            cached = tokenState.get();
            if (isValid(cached, now)) {
                return cached.accessToken();
            }

            final TokenRecord refreshed = tryRefreshToken(cached);
            if (refreshed != null) {
                tokenState.set(refreshed);
                return refreshed.accessToken();
            }

            final TokenRecord ropc = requestPasswordGrant();
            tokenState.set(ropc);
            return ropc.accessToken();
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isValid(TokenRecord tokenRecord, Instant now) {
        return tokenRecord != null && tokenRecord.isUsable(now, Math.toIntExact(clockSkewSeconds));
    }

    private TokenRecord tryRefreshToken(TokenRecord current) {
        if (current == null || !current.hasRefreshToken()) {
            return null;
        }

        LOGGER.log(System.Logger.Level.INFO, "Requesting new token via refresh_token flow.");

        Map<String, String> formParameters = new LinkedHashMap<>();
        formParameters.put("grant_type", "refresh_token");
        formParameters.put("refresh_token", current.refreshToken());
        formParameters.put("client_id", clientId);
        formParameters.put("client_secret", clientSecret);

        try {
            return requestToken(formParameters);
        } catch (RuntimeException ex) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Refresh-token flow failed, falling back to password flow. Error: {0}", ex.getMessage());
            return null;
        }
    }

    private TokenRecord requestPasswordGrant() {
        LOGGER.log(System.Logger.Level.INFO, "Requesting new token via password (ROPC) flow.");

        Map<String, String> formParameters = new LinkedHashMap<>();
        formParameters.put("grant_type", "password");
        formParameters.put("username", username);
        formParameters.put("password", password);
        formParameters.put("client_id", clientId);
        formParameters.put("client_secret", clientSecret);

        return requestToken(formParameters);
    }

    private TokenRecord requestToken(Map<String, String> formParameters) {
        final HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(buildFormBody(formParameters)))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final int statusCode = response.statusCode();
            final String responseBody = response.body();

            if (statusCode < 200 || statusCode >= 300) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "IdP token endpoint returned non-2xx status {0}.", statusCode);
                throw new RuntimeException("Token request failed");
            }

            return parseTokenResponse(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("I/O error during token request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Token request was interrupted", e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Token request failed", e);
        }
    }

    private TokenRecord parseTokenResponse(String responseBody) {
        try {
            final String accessToken = extractRequired(responseBody, ACCESS_TOKEN_PATTERN, "access_token");
            final String refreshToken = extractOptional(responseBody, REFRESH_TOKEN_PATTERN);
            final long expiresInSeconds = Long.parseLong(extractRequired(responseBody, EXPIRES_IN_PATTERN, "expires_in"));

            final Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);
            return new TokenRecord(accessToken, refreshToken, expiresAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token response", e);
        }
    }

    private String extractRequired(String body, Pattern pattern, String fieldName) {
        final String value = extractOptional(body, pattern);
        if (value == null) {
            throw new RuntimeException("Field '" + fieldName + "' missing in token response");
        }
        return value;
    }

    private String extractOptional(String body, Pattern pattern) {
        final Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String buildFormBody(Map<String, String> formParameters) {
        return formParameters.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
