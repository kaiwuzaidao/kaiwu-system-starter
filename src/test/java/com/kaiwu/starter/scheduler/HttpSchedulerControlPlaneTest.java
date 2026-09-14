package com.kaiwu.starter.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpSchedulerControlPlaneTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void synchronizesWithApplicationCredentialAndMapsStringIds() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/scheduler/sync", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body =
                    """
                    {"code":0,"message":"success","data":[{
                      "id":"9007199254740993001",
                      "projectId":"9007199254740993002",
                      "taskType":"report.daily",
                      "cronExpression":"0 0 1 * * *",
                      "zoneId":"Asia/Shanghai",
                      "payload":"{}",
                      "configVersion":7
                    }]}
                    """
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpSchedulerControlPlane client = new HttpSchedulerControlPlane(
                "http://127.0.0.1:" + server.getAddress().getPort(), "zsch_credential_secret");
        List<ProjectSchedulerJob> jobs = client.synchronize(
                "9007199254740993002",
                "instance-a",
                List.of(new ProjectSchedulerHandlerRegistration("report.daily", "日报生成")));

        assertThat(authorization).hasValue("Bearer zsch_credential_secret");
        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo("9007199254740993001");
            assertThat(job.projectId()).isEqualTo("9007199254740993002");
            assertThat(job.configVersion()).isEqualTo(7);
        });
    }

    @Test
    void renewsAnExecutionLeaseWithTheSameApplicationCredential() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/scheduler/executions/execution-1/renew", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body =
                    """
                            {"code":0,"message":"success","data":true}
                            """
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpSchedulerControlPlane client = new HttpSchedulerControlPlane(
                "http://127.0.0.1:" + server.getAddress().getPort(), "zsch_credential_secret");

        assertThat(client.renew("project-1", "instance-a", "execution-1")).isTrue();
        assertThat(requestBody.get()).contains("\"instanceId\":\"instance-a\"");
    }

    @Test
    void appliesExplicitReadTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/scheduler/sync", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(500);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        HttpSchedulerControlPlane client = new HttpSchedulerControlPlane(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "zsch_credential_secret",
                Duration.ofSeconds(1),
                Duration.ofMillis(50));

        assertThatThrownBy(() -> client.synchronize("project-1", "instance-a", List.of()))
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
    }
}
