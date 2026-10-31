package com.chubb.claims.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.ai.AgentModel.AssistantMsg;
import com.chubb.claims.ai.AgentModel.ModelReply;
import com.chubb.claims.ai.AgentModel.ToolCall;
import com.chubb.claims.ai.AgentModel.ToolResult;
import com.chubb.claims.ai.AgentModel.ToolResults;
import com.chubb.claims.ai.AgentModel.ToolSpec;
import com.chubb.claims.ai.AgentModel.UserMsg;
import com.chubb.claims.exception.AgentUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Gemini adapter against a local fake server: request shape, response parsing and failure handling. */
class GeminiModelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            path.set(ex.getRequestURI().toString());
            keyHeader.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiModel model(String key) {
        return new GeminiModel(key, "gemini-test", "http://127.0.0.1:" + server.getAddress().getPort(), 5, mapper);
    }

    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("submit_claim", "Raise a claim", Map.of("market", Map.of("type", "string")), List.of("market")),
            new ToolSpec("list_my_claims", "List claims", Map.of(), List.of()));

    @Test
    void sendsTheConversationAndToolsAndKeepsTheKeyOutOfTheUrl() throws Exception {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}";

        ModelReply r = model("secret-key").send("be helpful", List.of(new UserMsg("hi")), TOOLS);

        assertThat(r.text()).isEqualTo("Hello");
        assertThat(r.calls()).isEmpty();
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-test:generateContent").doesNotContain("secret-key");
        assertThat(keyHeader.get()).isEqualTo("secret-key");
        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.at("/systemInstruction/parts/0/text").asText()).isEqualTo("be helpful");
        assertThat(sent.at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(sent.at("/contents/0/parts/0/text").asText()).isEqualTo("hi");
        JsonNode decls = sent.at("/tools/0/functionDeclarations");
        assertThat(decls.get(0).at("/parameters/required/0").asText()).isEqualTo("market");
        assertThat(decls.get(1).has("parameters")).isFalse();   // no-argument tool: Gemini rejects empty properties
    }

    @Test
    void parsesAFunctionCallAndSendsTheResultBack() throws Exception {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"submit_claim\","
                + "\"args\":{\"market\":\"SG\"}}}]}}]}";

        ModelReply r = model("k").send("s", List.of(new UserMsg("raise")), TOOLS);

        assertThat(r.calls()).hasSize(1);
        ToolCall c = r.calls().get(0);
        assertThat(c.name()).isEqualTo("submit_claim");
        assertThat(c.input().get("market").asText()).isEqualTo("SG");

        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Raised.\"}]}}]}";
        ModelReply done = model("k").send("s", List.of(new UserMsg("raise"),
                new AssistantMsg("", r.calls(), r.raw()),
                new ToolResults(List.of(new ToolResult(c.id(), c.name(), "<tool_data>{}</tool_data>", false)))), TOOLS);

        assertThat(done.text()).isEqualTo("Raised.");
        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.at("/contents/1/role").asText()).isEqualTo("model");
        assertThat(sent.at("/contents/1/parts/0/functionCall/name").asText()).isEqualTo("submit_claim");   // echoed unchanged
        assertThat(sent.at("/contents/2/role").asText()).isEqualTo("user");
        assertThat(sent.at("/contents/2/parts/0/functionResponse/name").asText()).isEqualTo("submit_claim");
        assertThat(sent.at("/contents/2/parts/0/functionResponse/response/result").asText()).contains("tool_data");
    }

    @Test
    void aBlockedAnswerIsARefusalNotAnError() {
        reply = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        assertThat(model("k").send("s", List.of(new UserMsg("x")), TOOLS).refused()).isTrue();
    }

    @Test
    void rateLimitAndErrorsBecome503WithoutLeakingTheKey() {
        status = 429;
        assertThatThrownBy(() -> model("secret-key").send("s", List.of(new UserMsg("x")), TOOLS))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("rate limit");
        status = 400;
        reply = "{\"error\":{\"message\":\"API key not valid\"}}";
        assertThatThrownBy(() -> model("secret-key").send("s", List.of(new UserMsg("x")), TOOLS))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("secret-key");
    }

    @Test
    void anOverloadedModelIsRetriedOnceThenReportedAsBusy() {
        status = 503;
        reply = "{\"error\":{\"status\":\"UNAVAILABLE\"}}";
        assertThatThrownBy(() -> model("k").send("s", List.of(new UserMsg("x")), TOOLS))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("busy");
        assertThat(requests.get()).isEqualTo(2);   // the first call and exactly one retry
    }

    @Test
    void missingKeyIsReportedAsNotConfigured() {
        assertThatThrownBy(() -> model("").send("s", List.of(new UserMsg("x")), TOOLS))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("GEMINI_API_KEY");
    }
}
