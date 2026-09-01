package fun.commons.tokenroute.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDK 客户端（02 §6）：模式头 / 信封解析 / resolve 三元组 / lease 透传字���。JDK HttpServer 假上游。
 */
class TrRouteClientTest {

    static HttpServer server;
    static AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    static AtomicReference<String> lastCaller = new AtomicReference<>();
    static AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/resolve", ex -> respond(ex, resolveBody()));
        server.createContext("/v1/report", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthHeader.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
            respond(ex, "{\"code\":10700,\"message\":\"部分成功\",\"data\":{\"accepted\":1,"
                    + "\"rejected\":[{\"index\":1,\"code\":10400,\"message\":\"未知 entry_id\"}]},"
                    + "\"error\":[],\"trace_id\":\"t2\",\"timestamp\":1}");
        });
        server.start();
    }

    private static String resolveBody() {
        return "{\"code\":0,\"message\":\"ok\",\"data\":{\"entry_id\":\"e-1\",\"data_json\":{\"base_url\":\"https://a\"},"
                + "\"lease_id\":\"L-1\",\"affinity\":\"NEW\",\"reasons\":[]},\"error\":[],\"trace_id\":\"t1\",\"timestamp\":1}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        lastAuthHeader.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
        lastCaller.set(ex.getRequestHeaders().getFirst("X-Caller-Id"));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            lastAuthHeader.set(auth);
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void apiKeyModeSendsHeaderAndParsesResolveEnvelope() throws Exception {
        TrRouteClient client = TrRouteClient.apiKey(base(), "secret-9").callerId("gateway");
        TrRouteClient.Envelope<TrRouteClient.ResolveResult> env = client.resolve("llm", "task-1", Map.of("k", "v"));

        assertThat(lastAuthHeader.get()).isEqualTo("secret-9");
        assertThat(lastCaller.get()).isEqualTo("gateway");
        assertThat(env.isOk()).isTrue();
        assertThat(env.getData().getEntryId()).isEqualTo("e-1");
        assertThat(env.getData().getLeaseId()).isEqualTo("L-1");
        assertThat(env.getData().getDataJson()).containsEntry("base_url", "https://a");
        assertThat(env.getData().getAffinity()).isEqualTo("NEW");
    }

    @Test
    void jwtModeSendsBearerHeader() throws Exception {
        TrRouteClient client = TrRouteClient.jwt(base(), () -> "token-xyz");
        client.resolve("llm", null, null);
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer token-xyz");
    }

    @Test
    void reportParses10700RejectedDetails() throws Exception {
        TrRouteClient client = TrRouteClient.open(base());
        List<Map<String, Object>> reports = List.of(
                Map.of("entry_id", "e-1", "lease_id", "L-1", "result", "SUCCESS"),
                Map.of("entry_id", "e-x", "lease_id", "L-2", "result", "SUCCESS"));
        TrRouteClient.Envelope<TrRouteClient.ReportResult> env = client.report(reports);

        assertThat(env.getCode()).isEqualTo(10700);
        // lease 透传：body 携带 resolve 返回的 lease_id 原文
        assertThat(lastBody.get()).contains("L-1");
        assertThat(env.getData().getAccepted()).isEqualTo(1);
        assertThat(env.getData().getRejected()).hasSize(1);
        assertThat(env.getData().getRejected().get(0).getIndex()).isEqualTo(1);
        assertThat(env.getData().getRejected().get(0).getCode()).isEqualTo(10400);
    }

    @Test
    void noneModeSendsNoAuthHeader() throws Exception {
        lastAuthHeader.set(null);
        TrRouteClient client = TrRouteClient.open(base());
        client.resolve("llm", null, null);
        assertThat(lastAuthHeader.get()).isNull();
    }
}
