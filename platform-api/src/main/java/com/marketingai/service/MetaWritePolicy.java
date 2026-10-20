package com.marketingai.service;

import java.util.List;
import java.util.Locale;

/**
 * Pure decision table for every write to the Meta Marketing API (roadmap 9-19; .ai/meta-api-policy.md §6).
 *
 * Stages (tenants.meta_write_stage):
 *   READ_ONLY       — only PROTECTIVE writes (pausing spend) are executed
 *   GUARDED_WRITES  — + RESUME, BUDGET within thresholds, LAUNCH only as a moderation probe, CREATE_AD_SET as probe
 *   FULL            — everything, thresholds still apply
 *
 * Initiator: USER (admin UI) or JOB (scheduler/autopilot). JOB-initiated expansive writes additionally need
 * the tenant's autonomy level: SEMI_AUTOPILOT for RESUME/BUDGET/LAUNCH, AUTOPILOT for CREATE_CAMPAIGN/AUDIENCE.
 *
 * PROTECTIVE writes are never refused — stopping spend is insurance, not optimisation.
 */
public final class MetaWritePolicy {

    public enum Stage { READ_ONLY, GUARDED_WRITES, FULL }
    public enum Initiator { USER, JOB }
    public enum Verdict { ALLOW, DENY, RECOMMEND }

    public record Thresholds(double maxBudgetChangePct, double minHoursBetweenBudgetChanges,
                             int maxWritesPerHour, boolean learningPhaseLock, boolean launchRequiresProbe) {
        public static Thresholds defaults() { return new Thresholds(20.0, 24.0, 10, true, true); }
    }

    /** Facts gathered by the gate for one write. */
    public record Context(Stage stage, String autonomyLevel, Initiator initiator,
                          MetaWriteAction action, String targetId,
                          Double budgetDeltaPct, Double hoursSinceLastBudgetChange,
                          int writesLastHour, String targetLearningPhase, boolean probe,
                          boolean overrideActive) {}

    public record Decision(Verdict verdict, String reason) {
        public boolean allowed() { return verdict == Verdict.ALLOW; }
    }

    public static final List<String> STAGES = List.of("READ_ONLY", "GUARDED_WRITES", "FULL");

    private MetaWritePolicy() {}

    public static Stage parseStage(String s) {
        try { return Stage.valueOf(s == null ? "READ_ONLY" : s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return Stage.READ_ONLY; }
    }

    public static Decision evaluate(Context c, Thresholds t) {
        MetaWriteAction a = c.action();

        // 1. Protective writes: always, everywhere, for everyone.
        if (a.protective()) {
            return new Decision(Verdict.ALLOW, "protective write (" + a + ") is never gated");
        }

        // 2. Explicit, time-boxed override with a reason wins over stage and thresholds (not over learning-phase lock
        //    for budget — that is Meta's own rule and costs the learning phase).
        if (c.overrideActive()) {
            if (a == MetaWriteAction.BUDGET_CHANGE && t.learningPhaseLock() && "LEARNING".equalsIgnoreCase(c.targetLearningPhase())) {
                return new Decision(Verdict.DENY, "override cannot bypass the learning-phase lock for budget changes");
            }
            return new Decision(Verdict.ALLOW, "override active");
        }

        // 3. Stage gate.
        switch (c.stage()) {
            case READ_ONLY -> {
                return new Decision(Verdict.DENY, "stage READ_ONLY: only protective writes (pause/stop-loss) are executed; "
                        + "advance to GUARDED_WRITES when the readiness conditions pass");
            }
            case GUARDED_WRITES -> {
                if (a == MetaWriteAction.CREATE_CAMPAIGN || a == MetaWriteAction.AUDIENCE_UPDATE) {
                    return new Decision(Verdict.DENY, "stage GUARDED_WRITES: " + a + " requires stage FULL");
                }
                if ((a == MetaWriteAction.LAUNCH || a == MetaWriteAction.CREATE_AD_SET) && t.launchRequiresProbe() && !c.probe()) {
                    return new Decision(Verdict.DENY, "stage GUARDED_WRITES: " + a + " only as a moderation probe (ad sets created PAUSED until Meta's verdict)");
                }
            }
            case FULL -> { /* thresholds only */ }
        }

        // 4. Autonomy level for machine-initiated writes (humans decide for themselves inside the stage rules).
        if (c.initiator() == Initiator.JOB) {
            int rank = AutonomyGate.rank(c.autonomyLevel());
            boolean needsAutopilot = a == MetaWriteAction.CREATE_CAMPAIGN || a == MetaWriteAction.AUDIENCE_UPDATE;
            if (needsAutopilot && rank < AutonomyGate.rank("AUTOPILOT")) {
                return new Decision(Verdict.DENY, "autonomy " + c.autonomyLevel() + ": " + a + " needs AUTOPILOT for automatic execution");
            }
            if (rank < AutonomyGate.rank("SEMI_AUTOPILOT")) {
                return new Decision(Verdict.RECOMMEND, "autonomy " + c.autonomyLevel() + ": automatic " + a + " recorded as a recommendation for a human");
            }
        }

        // 5. Learning-phase lock (Meta resets learning on edits; vision §6.5).
        if (t.learningPhaseLock() && "LEARNING".equalsIgnoreCase(c.targetLearningPhase())
                && (a == MetaWriteAction.BUDGET_CHANGE || a == MetaWriteAction.RESUME)) {
            return new Decision(Verdict.DENY, "ad set is in LEARNING phase — edits reset learning; wait for ACTIVE/LEARNING_LIMITED");
        }

        // 6. Budget thresholds.
        if (a == MetaWriteAction.BUDGET_CHANGE) {
            if (c.budgetDeltaPct() != null && Math.abs(c.budgetDeltaPct()) > t.maxBudgetChangePct()) {
                return new Decision(Verdict.RECOMMEND, String.format(Locale.ROOT,
                        "budget change %.0f%% exceeds the %.0f%% cap per step — recorded as a recommendation", c.budgetDeltaPct(), t.maxBudgetChangePct()));
            }
            if (c.hoursSinceLastBudgetChange() != null && c.hoursSinceLastBudgetChange() < t.minHoursBetweenBudgetChanges()) {
                return new Decision(Verdict.RECOMMEND, String.format(Locale.ROOT,
                        "last budget change on this target was %.1f h ago (min %.0f h) — recorded as a recommendation",
                        c.hoursSinceLastBudgetChange(), t.minHoursBetweenBudgetChanges()));
            }
        }

        // 7. Rate limit per account (counts ALLOWed non-protective writes in the last hour).
        if (c.writesLastHour() >= t.maxWritesPerHour()) {
            return new Decision(Verdict.RECOMMEND, "write rate limit: " + c.writesLastHour() + " writes in the last hour (max "
                    + t.maxWritesPerHour() + ") — recorded as a recommendation");
        }

        return new Decision(Verdict.ALLOW, "within stage " + c.stage() + " and thresholds");
    }
}
