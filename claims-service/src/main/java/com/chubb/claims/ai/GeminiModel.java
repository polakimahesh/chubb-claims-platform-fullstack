package com.chubb.claims.ai;

import com.chubb.claims.exception.AgentUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Gemini over its REST API (generateContent with function calling). The key is sent in a header, never in the
 * URL, so it cannot end up in access logs, and it is never logged.
 */
@Slf4j
@Component
class GeminiModel implements AgentModel {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private static final Duration RETRY_PAUSE = Duration.ofMillis(1500);
    private final HttpClient http = HttpClient.newHttpClient();

    GeminiModel(@Value("${app.ai.gemini.api-key:}") String apiKey,
                @Value("${app.ai.gemini.model:gemini-flash-lite-latest}") String model,
                @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
                @Value("${app.ai.timeout-seconds:30}") int timeoutSeconds,
                ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.mapper = mapper;
    }

    @Override
    public ModelReply send(String system, List<Msg> history, List<ToolSpec> tools) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AgentUnavailableException(
                    "The AI assistant is not configured: set app.ai.gemini.api-key (or GEMINI_API_KEY).");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body(system, history, tools))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) {   // Google sheds load in spikes; one short retry usually clears it
                Thread.sleep(RETRY_PAUSE.toMillis());
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() == 429) {
                throw new AgentUnavailableException("The AI assistant hit its rate limit. Please wait a moment and retry.");
            }
            if (response.statusCode() >= 500) {
                log.warn("Gemini returned HTTP {}: {}", response.statusCode(), abbreviate(response.body()));
                throw new AgentUnavailableException("The AI model is busy right now. Please try again in a moment.");
            }
            if (response.statusCode() / 100 != 2) {
                log.warn("Gemini returned HTTP {}: {}", response.statusCode(), abbreviate(response.body()));
                throw new AgentUnavailableException("The AI assistant could not use the model (HTTP "
                        + response.statusCode() + "). Check the API key and model name.");
            }
            return parse(mapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("The AI assistant was interrupted.", e);
        } catch (IOException e) {
            throw new AgentUnavailableException("The AI assistant could not reach the model. Please try again.", e);
        }
    }

    private ObjectNode body(String system, List<Msg> history, List<ToolSpec> tools) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", system);

        ArrayNode contents = root.putArray("contents");
        for (Msg m : history) {
            if (m instanceof UserMsg u) {
                content(contents, "user").addObject().put("text", u.text());
            } else if (m instanceof AssistantMsg a) {
                if (a.raw() instanceof JsonNode parts) {
                    content(contents, "model").addAll((ArrayNode) parts);   // unchanged, as Gemini returned it
                } else {
                    content(contents, "model").addObject().put("text", a.text());
                }
            } else if (m instanceof ToolResults r) {
                ArrayNode parts = content(contents, "user");
                for (ToolResult t : r.results()) {
                    ObjectNode fr = parts.addObject().putObject("functionResponse");
                    fr.put("name", t.name());
                    fr.putObject("response").put(t.error() ? "error" : "result", t.text());
                }
            }
        }

        if (!tools.isEmpty()) {
            ArrayNode decls = root.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec t : tools) {
                ObjectNode d = decls.addObject();
                d.put("name", t.name());
                d.put("description", t.description());
                if (!t.properties().isEmpty()) {   // Gemini rejects an object schema with no properties
                    ObjectNode schema = d.putObject("parameters");
                    schema.put("type", "object");
                    schema.set("properties", mapper.valueToTree(t.properties()));
                    if (!t.required().isEmpty()) {
                        schema.set("required", mapper.valueToTree(t.required()));
                    }
                }
            }
        }
        root.putObject("generationConfig").put("maxOutputTokens", 8192);
        return root;
    }

    private static ArrayNode content(ArrayNode contents, String role) {
        ObjectNode c = contents.addObject();
        c.put("role", role);
        return c.putArray("parts");
    }

    private ModelReply parse(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            // no candidate, or one without content: the prompt or answer was blocked
            return new ModelReply("", List.of(), true, null);
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            JsonNode part = parts.get(i);
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                String name = fc.path("name").asText();
                calls.add(new ToolCall(name + "#" + i, name, fc.has("args") ? fc.get("args") : mapper.createObjectNode()));
            } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                text.append(part.get("text").asText());
            }
        }
        return new ModelReply(text.toString().trim(), calls, false, parts);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
