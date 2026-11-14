package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpAgentRegistryTransportTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(reply());
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/credential-leak");
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), 0);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AgentRegistryProperties properties() {
        AgentRegistryProperties p = new AgentRegistryProperties();
        p.setSystemBaseUrl("http://localhost:" + server.getAddress().getPort());
        p.setCredential("kagent_1789248125900000001_" + "a".repeat(43));
        return p;
    }

    static String reply() {
        return "{\"code\":0,\"data\":{\"projectId\":\"1789248125900000001\",\"capabilityCode\":\"crm.record.list\",\"version\":\"1\",\"manifestHash\":\""
                + "a".repeat(64) + "\",\"status\":\"PENDING\"}}";
    }

    @Test
    void roundTripUsesFixedPathsSeparateCredentialAndReturnedRevision() {
        AtomicInteger resolved = new AtomicInteger();
        try (var transport = new HttpAgentRegistryTransport(properties(), address -> {
            resolved.incrementAndGet();
            return address.url();
        })) {
            var revision = transport.register("crm.record.list", "1", "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(path.get()).isEqualTo("/api/internal/agent/capabilities/register");
            assertThat(authorization.get()).isEqualTo("Bearer " + properties().getCredential());
            transport.heartbeat(revision);
            assertThat(path.get()).isEqualTo("/api/internal/agent/capabilities/heartbeat");
            assertThat(body.get())
                    .contains(revision.manifestHash())
                    .doesNotContain(properties().getCredential());
            assertThat(resolved.get()).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 409, 413, 429, 503})
    void preservesStatusWithoutLeakingServerResponseOrFollowingRedirect(int code) {
        status.set(code);
        response.set(properties().getCredential());
        try (var transport = new HttpAgentRegistryTransport(properties(), ServiceAddressResolver.direct())) {
            assertThatThrownBy(() -> transport.register("crm.record.list", "1", new byte[0]))
                    .isInstanceOfSatisfying(AgentRegistryException.class, e -> {
                        assertThat(e.status()).isEqualTo(code);
                        assertThat(e.getMessage()).doesNotContain(properties().getCredential());
                    });
        }
    }

    @Test
    void rejectsWrongProjectVersionDigestTypeDuplicateKeysAndOversizedResponses() {
        try (var transport = new HttpAgentRegistryTransport(properties(), ServiceAddressResolver.direct())) {
            for (String invalid : new String[] {
                reply().replace("1789248125900000001", "1789248125900000002"),
                reply().replace("\"version\":\"1\"", "\"version\":1"),
                reply().replace("PENDING", "REVOKED"),
                reply().replace("\"code\":0", "\"code\":0,\"code\":0"),
                reply() + " {}",
                "x".repeat(70000)
            }) {
                response.set(invalid);
                assertThatThrownBy(() -> transport.register("crm.record.list", "1", new byte[0]))
                        .isInstanceOf(AgentRegistryException.class);
            }
            response.set(reply());
            assertThatThrownBy(() -> transport.heartbeat(
                            new AgentRegistryTransport.Revision("crm.record.list", "1", "b".repeat(64))))
                    .isInstanceOf(AgentRegistryException.class);
        }
    }

    @Test
    void rejectsCredentialFallbackAndUnsafeBaseAddresses() {
        var p = properties();
        p.setCredential("zsch_1_secret");
        assertThatThrownBy(() -> new HttpAgentRegistryTransport(p, ServiceAddressResolver.direct()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String address : new String[] {
            "http://user:password@localhost",
            "http://localhost/?token=secret",
            "http://localhost/prefix",
            "file:///tmp/secret"
        }) {
            var invalid = properties();
            invalid.setSystemBaseUrl(address);
            assertThatThrownBy(() -> new HttpAgentRegistryTransport(invalid, ServiceAddressResolver.direct()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(address);
        }
    }

    @Test
    void boundsWholeRequestIncludingAStalledResponseBody() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var p = properties();
        p.setTimeoutMillis(150);
        try (var transport = new HttpAgentRegistryTransport(p, ServiceAddressResolver.direct())) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.register("crm.record.list", "1", new byte[0]))
                    .isInstanceOf(AgentRegistryException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
    }
}
