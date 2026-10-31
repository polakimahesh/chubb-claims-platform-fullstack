package com.chubb.claims.ai;

import com.chubb.claims.dto.ClaimRequests;
import com.chubb.claims.events.ClaimType;
import com.chubb.claims.events.Market;
import com.chubb.claims.exception.BusinessRuleException;
import com.chubb.claims.exception.ClaimNotFoundException;
import com.chubb.claims.exception.ForbiddenException;
import com.chubb.claims.exception.InvalidRequestException;
import com.chubb.claims.exception.InvalidStateTransitionException;
import com.chubb.claims.service.ClaimService;
import com.chubb.platform.security.Actor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What the AI assistant may do. Every tool is a thin call into {@link ClaimService} as the signed-in user, so the
 * lifecycle rules, record-level access and audit trail apply exactly as they do for the REST API. The tool list is
 * chosen by role and the role is checked again on execution, so the model can never widen its own permissions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimsAgentTools {

    /** Result of one tool call: the JSON the model reads, a one-line note for the UI, and whether it worked. */
    public record Outcome(String json, String summary, boolean ok) {
    }

    private final ClaimService service;
    private final ObjectMapper mapper;
    private final Validator validator;

    /** The tools offered to this caller. */
    public List<AgentModel.ToolSpec> toolsFor(Actor actor) {
        List<AgentModel.ToolSpec> tools = new ArrayList<>();
        tools.add(tool("get_claim", "Show one claim by id.", Map.of("claim_id", string("Claim id (UUID)")),
                List.of("claim_id")));
        if (actor.isClaimant()) {
            tools.add(tool("list_my_claims", "List the signed-in claimant's own claims, newest first.", Map.of(), List.of()));
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("claimant_name", string("Full name of the claimant"));
            props.put("market", enumOf(Market.class, "Market where the policy is held"));
            props.put("claim_type", enumOf(ClaimType.class, "Kind of claim"));
            props.put("description", string("What happened, in the claimant's words"));
            props.put("incident_date", string("Date of the incident, YYYY-MM-DD, not in the future"));
            props.put("estimated_amount", Map.of("type", "number", "description",
                    "Estimated loss in the market's own currency, above 0, at most 2 decimals"));
            tools.add(tool("submit_claim", "Raise a new claim for the signed-in claimant. The currency is set from the market.",
                    props, List.of("claimant_name", "market", "claim_type", "description", "incident_date",
                            "estimated_amount")));
        }
        if (actor.isOfficer()) {
            tools.add(tool("list_queue", "List unassigned claims waiting for an officer, oldest first.", Map.of(), List.of()));
            tools.add(tool("assign_claim", "Pick up an unassigned claim for the signed-in officer.",
                    Map.of("claim_id", string("Claim id (UUID)")), List.of("claim_id")));
            tools.add(tool("assess_claim", "Record the assessed amount on a claim assigned to the signed-in officer.",
                    Map.of("claim_id", string("Claim id (UUID)"),
                            "assessed_amount", Map.of("type", "number", "description", "Assessed amount, above 0"),
                            "note", string("Optional note")),
                    List.of("claim_id", "assessed_amount")));
            tools.add(tool("approve_claim", "Approve an assessed claim assigned to the signed-in officer.",
                    Map.of("claim_id", string("Claim id (UUID)")), List.of("claim_id")));
            tools.add(tool("reject_claim", "Reject a claim assigned to the signed-in officer, with a reason.",
                    Map.of("claim_id", string("Claim id (UUID)"), "reason", string("Why the claim is rejected")),
                    List.of("claim_id", "reason")));
        }
        return tools;
    }

    /** Runs one tool as {@code actor}. Never throws: failures come back as an error outcome the model can explain. */
    public Outcome execute(Actor actor, String name, JsonNode in) {
        try {
            return switch (name) {
                case "get_claim" -> ok(service.get(uuid(in, "claim_id"), actor), "Looked up a claim");
                case "list_my_claims" -> {
                    requireClaimant(actor);
                    yield ok(service.listMine(actor, 0, 20), "Listed your claims");
                }
                case "submit_claim" -> {
                    requireClaimant(actor);
                    var created = service.submit(actor, valid(submitRequest(in)));
                    yield ok(created, "Raised claim " + created.claimNumber());
                }
                case "list_queue" -> {
                    requireOfficer(actor);
                    yield ok(service.queue(null, null, 0, 20), "Listed the unassigned queue");
                }
                case "assign_claim" -> {
                    requireOfficer(actor);
                    var claim = service.assign(uuid(in, "claim_id"), actor);
                    yield ok(claim, "Assigned " + claim.claimNumber() + " to you");
                }
                case "assess_claim" -> {
                    requireOfficer(actor);
                    var claim = service.assess(uuid(in, "claim_id"), actor, valid(new ClaimRequests.Assess(
                            decimal(in, "assessed_amount"), text(in, "note", false))));
                    yield ok(claim, "Assessed " + claim.claimNumber() + " at " + claim.assessedAmount());
                }
                case "approve_claim" -> {
                    requireOfficer(actor);
                    var claim = service.approve(uuid(in, "claim_id"), actor);
                    yield ok(claim, "Approved " + claim.claimNumber());
                }
                case "reject_claim" -> {
                    requireOfficer(actor);
                    var claim = service.reject(uuid(in, "claim_id"), actor,
                            valid(new ClaimRequests.Reject(text(in, "reason", true))));
                    yield ok(claim, "Rejected " + claim.claimNumber());
                }
                default -> new Outcome("{\"error\":\"Unknown tool\"}", "Unknown tool " + name, false);
            };
        } catch (ForbiddenException | BusinessRuleException | InvalidRequestException
                 | ClaimNotFoundException | InvalidStateTransitionException e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Assistant tool {} failed for {}", name, actor.username(), e);
            return failure("The action could not be completed.");
        }
    }

    // ---------- helpers ----------

    private Outcome ok(Object result, String summary) {
        try {
            return new Outcome(mapper.writeValueAsString(result), summary, true);
        } catch (JsonProcessingException e) {
            return failure("The result could not be read.");
        }
    }

    private Outcome failure(String message) {
        try {
            return new Outcome(mapper.writeValueAsString(Map.of("error", message)), message, false);
        } catch (JsonProcessingException e) {
            return new Outcome("{\"error\":\"failed\"}", message, false);
        }
    }

    /** The same rules the REST API applies through @Valid: the model's input is trusted no more than a client's. */
    private <T> T valid(T request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations.stream()
                    .map((ConstraintViolation<T> v) -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted().collect(Collectors.joining("; ")));
        }
        return request;
    }

    private static void requireClaimant(Actor actor) {
        if (!actor.isClaimant()) {
            throw new ForbiddenException("Only a claimant can do this");
        }
    }

    private static void requireOfficer(Actor actor) {
        if (!actor.isOfficer()) {
            throw new ForbiddenException("Only an officer can do this");
        }
    }

    private static ClaimRequests.Submit submitRequest(JsonNode in) {
        Market market = parseEnum(Market.class, text(in, "market", true));
        return new ClaimRequests.Submit(
                text(in, "claimant_name", true),
                market,
                parseEnum(ClaimType.class, text(in, "claim_type", true)),
                text(in, "description", true),
                date(in, "incident_date"),
                market.currency(),
                decimal(in, "estimated_amount"));
    }

    private static String text(JsonNode in, String field, boolean required) {
        JsonNode n = in.get(field);
        if (n == null || n.isNull() || (n.isTextual() && n.asText().isBlank())) {
            if (required) {
                throw new InvalidRequestException("Missing " + field);
            }
            return null;
        }
        if (!n.isTextual() && !n.isNumber()) {
            throw new InvalidRequestException(field + " must be text or a number");
        }
        return n.asText().trim();
    }

    private static UUID uuid(JsonNode in, String field) {
        try {
            return UUID.fromString(text(in, field, true));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(field + " must be a claim id (UUID); list the claims to find it");
        }
    }

    private static LocalDate date(JsonNode in, String field) {
        try {
            return LocalDate.parse(text(in, field, true));
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(field + " must be YYYY-MM-DD");
        }
    }

    private static BigDecimal decimal(JsonNode in, String field) {
        try {
            BigDecimal value = new BigDecimal(text(in, field, true));
            if (value.precision() - value.scale() > 13 || value.scale() < -13) {   // also stops "1e999999999"
                throw new InvalidRequestException(field + " is too large");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidRequestException(field + " must be a number");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(value + " is not one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static <E extends Enum<E>> Map<String, Object> enumOf(Class<E> type, String description) {
        return Map.of("type", "string", "description", description,
                "enum", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }

    private static AgentModel.ToolSpec tool(String name, String description, Map<String, Object> properties,
                                            List<String> required) {
        return new AgentModel.ToolSpec(name, description, properties, required);
    }
}
