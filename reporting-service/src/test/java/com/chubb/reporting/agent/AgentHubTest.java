package com.chubb.reporting.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.events.ClaimEvent;
import com.chubb.claims.events.ClaimEventType;
import com.chubb.claims.events.ClaimStatus;
import com.chubb.claims.events.ClaimType;
import com.chubb.claims.events.Market;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentHubTest {

    private final AgentHub hub = new AgentHub(List.of(
            new DemoAgents.TriageAgent(), new DemoAgents.FraudWatchAgent(), new DemoAgents.WorkflowAgent()));

    private static ClaimEvent submitted(String amount) {
        return new ClaimEvent(UUID.randomUUID(), ClaimEventType.CLAIM_SUBMITTED, UUID.randomUUID(), "CLM-1", 1,
                Instant.now(), Market.values()[0], ClaimType.values()[0], ClaimStatus.SUBMITTED, null, "USD",
                new BigDecimal(amount), null, Instant.now(), null, "a@b.c", "A", null);
    }

    @Test
    void routineClaimGetsTriageAndWorkflowInsightsOnly() {
        hub.onEvent(submitted("500"));
        assertThat(hub.recent()).extracting(AgentInsight::agent)
                .containsExactlyInAnyOrder("Triage agent", "Workflow agent");
    }

    @Test
    void hugeClaimIsFlaggedByFraudWatchAsAlert() {
        hub.onEvent(submitted("250000"));
        assertThat(hub.recent()).anySatisfy(i -> {
            assertThat(i.agent()).isEqualTo("Fraud-watch agent");
            assertThat(i.severity()).isEqualTo(AgentInsight.Severity.ALERT);
        });
    }

    @Test
    void keepsOnlyTheMostRecentInsights() {
        for (int n = 0; n < 40; n++) hub.onEvent(submitted("500"));
        assertThat(hub.recent()).hasSize(AgentHub.KEEP);
    }
}
