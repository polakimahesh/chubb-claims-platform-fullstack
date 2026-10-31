package com.chubb.claims.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * The one call the assistant makes to a language model, in terms that do not depend on any provider. {@link GeminiModel}
 * is the implementation; tests script this interface so no network call or API key is needed, and another provider
 * would only need its own adapter.
 */
public interface AgentModel {

    ModelReply send(String system, List<Msg> history, List<ToolSpec> tools);

    /** One entry of the conversation the model sees. */
    sealed interface Msg permits UserMsg, AssistantMsg, ToolResults {
    }

    record UserMsg(String text) implements Msg {
    }

    /** {@code raw} is the provider's own form of this turn, sent back unchanged (providers need that for tool calls). */
    record AssistantMsg(String text, List<ToolCall> calls, Object raw) implements Msg {
    }

    record ToolResults(List<ToolResult> results) implements Msg {
    }

    record ToolCall(String id, String name, JsonNode input) {
    }

    record ToolResult(String callId, String name, String text, boolean error) {
    }

    /** A tool the model may call; {@code properties} is the JSON-schema property map. */
    record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {
    }

    record ModelReply(String text, List<ToolCall> calls, boolean refused, Object raw) {
    }
}
