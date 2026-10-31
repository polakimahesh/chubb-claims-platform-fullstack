package com.chubb.reporting.agent;

import com.chubb.claims.events.ClaimEvent;
import com.chubb.claims.events.ClaimEventType;
import com.chubb.reporting.agent.AgentInsight.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The demo agents. Rule-based on purpose: instant, repeatable and needing no external service or API key. */
public final class DemoAgents {

    private DemoAgents() {}

    static AgentInsight insight(String agent, Severity severity, ClaimEvent e, String message) {
        return new AgentInsight(UUID.randomUUID(), agent, severity, e.claimId(), e.claimNumber(), message, Instant.now());
    }

    private static String money(ClaimEvent e, BigDecimal amount) {
        return e.currency() + " " + amount.stripTrailingZeros().toPlainString();
    }

    /** Sets a priority for every newly submitted claim from its estimated amount. */
    @Component
    public static class TriageAgent implements ClaimAgent {
        static final BigDecimal HIGH = new BigDecimal("50000");
        static final BigDecimal MEDIUM = new BigDecimal("10000");

        @Override public String name() { return "Triage agent"; }

        @Override
        public Optional<AgentInsight> observe(ClaimEvent e) {
            if (e.type() != ClaimEventType.CLAIM_SUBMITTED || e.estimatedAmount() == null) return Optional.empty();
            BigDecimal amount = e.estimatedAmount();
            if (amount.compareTo(HIGH) >= 0) {
                return Optional.of(insight(name(), Severity.ALERT, e,
                        "High priority: " + e.claimType() + " claim estimated at " + money(e, amount) + " - assign a senior officer."));
            }
            if (amount.compareTo(MEDIUM) >= 0) {
                return Optional.of(insight(name(), Severity.WARNING, e,
                        "Medium priority: estimated at " + money(e, amount) + "."));
            }
            return Optional.of(insight(name(), Severity.INFO, e,
                    "Routine claim: estimated at " + money(e, amount) + " - standard handling."));
        }
    }

    /** Flags suspiciously large claims for a second look. */
    @Component
    public static class FraudWatchAgent implements ClaimAgent {
        static final BigDecimal THRESHOLD = new BigDecimal("100000");

        @Override public String name() { return "Fraud-watch agent"; }

        @Override
        public Optional<AgentInsight> observe(ClaimEvent e) {
            if (e.type() != ClaimEventType.CLAIM_SUBMITTED || e.estimatedAmount() == null
                    || e.estimatedAmount().compareTo(THRESHOLD) < 0) return Optional.empty();
            return Optional.of(insight(name(), Severity.ALERT, e,
                    "Unusually large claim (" + money(e, e.estimatedAmount()) + ") - recommend document verification before approval."));
        }
    }

    /** Suggests the next step as a claim moves through its lifecycle. */
    @Component
    public static class WorkflowAgent implements ClaimAgent {
        @Override public String name() { return "Workflow agent"; }

        @Override
        public Optional<AgentInsight> observe(ClaimEvent e) {
            String who = e.assignedOfficerId() == null ? "an officer" : e.assignedOfficerId();
            return switch (e.type()) {
                case CLAIM_SUBMITTED -> Optional.of(insight(name(), Severity.INFO, e,
                        "New claim waiting in the intake queue - needs an officer."));
                case CLAIM_ASSIGNED, CLAIM_REASSIGNED -> Optional.of(insight(name(), Severity.INFO, e,
                        "Now with " + who + " - review and assess the claim."));
                case INFO_REQUESTED -> Optional.of(insight(name(), Severity.WARNING, e,
                        "Waiting on the claimant" + (e.note() == null ? "." : ": " + e.note()) + " The SLA clock is paused."));
                case INFO_PROVIDED -> Optional.of(insight(name(), Severity.INFO, e,
                        "Claimant replied - " + who + " can continue the review."));
                case CLAIM_ASSESSED -> Optional.of(insight(name(), Severity.INFO, e,
                        "Assessed at " + (e.assessedAmount() == null ? "n/a" : money(e, e.assessedAmount())) + " - ready for a decision."));
                case CLAIM_APPROVED -> Optional.of(insight(name(), Severity.INFO, e,
                        "Approved - next step is settlement."));
                case CLAIM_REJECTED -> Optional.of(insight(name(), Severity.WARNING, e,
                        "Rejected" + (e.note() == null ? "." : ": " + e.note())));
                case CLAIM_SETTLED -> Optional.of(insight(name(), Severity.INFO, e,
                        "Settled and closed - no outstanding exposure."));
            };
        }
    }
}
