package com.chubb.claims.ai;

import static com.chubb.claims.support.TestAuth.claimant;
import static com.chubb.claims.support.TestAuth.manager;
import static com.chubb.claims.support.TestAuth.officer1;
import static com.chubb.claims.support.TestAuth.otherClaimant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.ai.AgentModel.ModelReply;
import com.chubb.claims.ai.AgentModel.ToolCall;
import com.chubb.claims.ai.AgentModel.ToolSpec;
import com.chubb.claims.exception.AgentUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The AI assistant end to end, with the model scripted so no network call or API key is needed. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"claims.outbox.enabled=false", "app.documents.dir=build/test-documents"})
class AgentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AgentModel model;

    private static final String CHAT = "{\"messages\":[{\"role\":\"user\",\"content\":\"Raise a motor claim in Singapore\"}]}";
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";
    private static final Map<String, Object> GOOD = Map.of("claimant_name", "Tan Wei", "market", "SG",
            "claim_type", "MOTOR", "description", "Rear-ended", "incident_date", "2026-10-01", "estimated_amount", 100);

    private ModelReply call(String name, Map<String, Object> input) {
        return new ModelReply("", List.of(new ToolCall("c1", name, json.valueToTree(input))), false, null);
    }

    private static ModelReply text(String reply) {
        return new ModelReply(reply, List.of(), false, null);
    }

    private ResultActions chat(org.springframework.test.web.servlet.request.RequestPostProcessor user) throws Exception {
        return mvc.perform(post("/api/agent/chat").with(user).contentType(MediaType.APPLICATION_JSON).content(CHAT));
    }

    @Test
    void claimantAskAgentRaisesRealClaimAsThemselves() throws Exception {
        when(model.send(any(), any(), any())).thenReturn(
                call("submit_claim", Map.of("claimant_name", "Tan Wei", "market", "SG", "claim_type", "MOTOR",
                        "description", "Rear-ended at a junction", "incident_date", "2026-10-01",
                        "estimated_amount", 8000)),
                text("I have raised your claim."));

        chat(claimant()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("I have raised your claim."))
                .andExpect(jsonPath("$.actions[0].tool").value("submit_claim"))
                .andExpect(jsonPath("$.actions[0].ok").value(true));

        // the claim exists, filed under the signed-in claimant, in the market's currency
        mvc.perform(get("/api/claims").with(claimant())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.estimatedAmount == 8000 && @.currency == 'SGD')]").isNotEmpty());
    }

    @Test
    void claimantIsOfferedOnlyClaimantTools() throws Exception {
        when(model.send(any(), any(), any())).thenReturn(text("Hi"));

        chat(claimant()).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolSpec>> offered = ArgumentCaptor.forClass(List.class);
        verify(model).send(any(), any(), offered.capture());
        assertThat(offered.getValue()).extracting(ToolSpec::name)
                .contains("submit_claim", "list_my_claims").doesNotContain("approve_claim", "reject_claim");
    }

    @Test
    void modelCannotWidenPermissionsEvenIfItCallsAToolItWasNotOffered() throws Exception {
        when(model.send(any(), any(), any())).thenReturn(call("approve_claim", Map.of("claim_id", UNKNOWN_ID)),
                text("I could not approve that."));

        chat(claimant()).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].tool").value("approve_claim"))
                .andExpect(jsonPath("$.actions[0].ok").value(false))
                .andExpect(jsonPath("$.actions[0].summary").value("Only an officer can do this"));
    }

    @Test
    void domainErrorsComeBackToTheModelNotAsServerErrors() throws Exception {
        when(model.send(any(), any(), any())).thenReturn(call("assign_claim", Map.of("claim_id", UNKNOWN_ID)),
                text("That claim does not exist."));

        chat(officer1()).andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].ok").value(false));
    }

    @Test
    void managerAndAnonymousCannotUseTheAssistant() throws Exception {
        chat(manager()).andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidChatBodyIs400() throws Exception {
        for (String body : List.of(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"x\"}]}",
                "{\"messages\":[]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(4001) + "\"}]}")) {
            mvc.perform(post("/api/agent/chat").with(claimant()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    /** Runs one submit_claim with a single bad field through the real controller. */
    private ResultActions submitWith(String field, Object value) throws Exception {
        Map<String, Object> input = new HashMap<>(GOOD);
        input.put(field, value);
        when(model.send(any(), any(), any())).thenReturn(call("submit_claim", input), text("ok"));
        return chat(claimant()).andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].ok").value(false));
    }

    @Test
    void toolInputIsValidatedLikeTheRestApi() throws Exception {
        submitWith("incident_date", "2999-01-01").andExpect(jsonPath("$.actions[0].summary").value(containsString("incidentDate")));
        submitWith("estimated_amount", -5).andExpect(jsonPath("$.actions[0].summary").value(containsString("estimatedAmount")));
        submitWith("estimated_amount", 1.234).andExpect(jsonPath("$.actions[0].summary").value(containsString("estimatedAmount")));
        submitWith("estimated_amount", "1e999999999").andExpect(jsonPath("$.actions[0].summary").value("estimated_amount is too large"));
        submitWith("description", "x".repeat(4001)).andExpect(jsonPath("$.actions[0].summary").value(containsString("description")));
        submitWith("market", "XX").andExpect(jsonPath("$.actions[0].summary").value(containsString("is not one of")));
    }

    @Test
    void stoppingAtTheTurnLimitStillReportsWhatWasDone() throws Exception {
        when(model.send(any(), any(), any())).thenReturn(call("submit_claim", GOOD));   // never stops calling tools

        chat(claimant()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(containsString("stopped after")))
                .andExpect(jsonPath("$.reply").value(containsString("Done so far")))
                .andExpect(jsonPath("$.actions").isNotEmpty());
    }

    @Test
    void modelOutageIs503ThroughTheController() throws Exception {
        when(model.send(any(), any(), any())).thenThrow(new AgentUnavailableException("The AI assistant is not configured."));

        chat(claimant()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The AI assistant is not configured."));
    }

    @Test
    void refusalIsAPoliteReplyNotAnError() throws Exception {
        when(model.send(any(), any(), any())).thenReturn(new ModelReply("", List.of(), true, null));

        chat(claimant()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("I can't help with that request."));
    }

    @Test
    void claimantCannotReadSomeoneElsesClaimThroughTheAssistant() throws Exception {
        String lee = mvc.perform(post("/api/claims").with(otherClaimant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claimantName\":\"Lee\",\"market\":\"SG\",\"claimType\":\"MOTOR\",\"description\":\"Dent\","
                                + "\"incidentDate\":\"2026-10-01\",\"currency\":\"SGD\",\"estimatedAmount\":500}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(lee).get("id").asText();
        when(model.send(any(), any(), any())).thenReturn(call("get_claim", Map.of("claim_id", id)), text("Not found."));

        chat(claimant()).andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].ok").value(false));
    }
}
