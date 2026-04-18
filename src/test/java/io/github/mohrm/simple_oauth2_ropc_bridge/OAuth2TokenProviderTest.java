package io.github.mohrm.simple_oauth2_ropc_bridge;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2TokenProviderTest {

    @Test
    void shouldReturnCachedTokenWhenStillValid() {
        RecordingHttpClient httpClient = new RecordingHttpClient();

        OAuth2TokenProvider provider = new OAuth2TokenProvider(
                httpClient,
                URI.create("https://idp.example.org/oauth/token"),
                "client-id",
                "client-secret",
                "user",
                "password"
        );

        seedToken(provider, new TokenRecord("cached-token", "refresh-token", Instant.now().plusSeconds(600)));

        String token = provider.getAccessToken();

        assertThat(token).isEqualTo("cached-token");
        assertThat(httpClient.requestBodies).isEmpty();
    }

    @Test
    void shouldUseRefreshTokenFlowWhenAccessTokenExpired() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.enqueueResponse(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}");

        OAuth2TokenProvider provider = new OAuth2TokenProvider(
                httpClient,
                URI.create("https://idp.example.org/oauth/token"),
                "client-id",
                "client-secret",
                "user",
                "password"
        );

        seedToken(provider, new TokenRecord("expired", "refresh-token", Instant.now().minusSeconds(10)));

        String token = provider.getAccessToken();

        assertThat(token).isEqualTo("new-access");
        assertThat(httpClient.requestBodies).hasSize(1);
        assertThat(httpClient.requestBodies.get(0)).contains("grant_type=refresh_token");
    }

    @Test
    void shouldFallbackToPasswordFlowWhenRefreshFails() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.enqueueResponse(401, "refresh rejected");
        httpClient.enqueueResponse(200, "{\"access_token\":\"ropc-access\",\"refresh_token\":\"ropc-refresh\",\"expires_in\":1200}");

        OAuth2TokenProvider provider = new OAuth2TokenProvider(
                httpClient,
                URI.create("https://idp.example.org/oauth/token"),
                "client-id",
                "client-secret",
                "user",
                "password"
        );

        seedToken(provider, new TokenRecord("expired", "refresh-token", Instant.now().minusSeconds(10)));

        String token = provider.getAccessToken();

        assertThat(token).isEqualTo("ropc-access");
        assertThat(httpClient.requestBodies).hasSize(2);
        assertThat(httpClient.requestBodies.get(0)).contains("grant_type=refresh_token");
        assertThat(httpClient.requestBodies.get(1)).contains("grant_type=password");
    }


    private static void seedToken(OAuth2TokenProvider provider, TokenRecord tokenRecord) {
        try {
            var field = OAuth2TokenProvider.class.getDeclaredField("tokenState");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ref = (java.util.concurrent.atomic.AtomicReference<TokenRecord>) field.get(provider);
            ref.set(tokenRecord);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
    private static final class RecordingHttpClient extends HttpClient {

        private final Queue<HttpResponse<String>> responses = new ArrayDeque<>();
        private final List<String> requestBodies = new java.util.ArrayList<>();

        void enqueueResponse(int statusCode, String body) {
            responses.add(new StubHttpResponse(statusCode, body));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requestBodies.add(readBody(request));
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) responses.remove();
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("Not needed in tests");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("Not needed in tests");
        }

        private String readBody(HttpRequest request) {
            HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
            BodyCollector subscriber = new BodyCollector();
            publisher.subscribe(subscriber);
            return subscriber.awaitBody();
        }
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {

        private final StringBuilder body = new StringBuilder();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            body.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            throw new RuntimeException(throwable);
        }

        @Override
        public void onComplete() {
            // no-op
        }

        String awaitBody() {
            return body.toString();
        }
    }

    private record StubHttpResponse(int statusCode, String body) implements HttpResponse<String> {

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (k, v) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://idp.example.org/oauth/token");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
