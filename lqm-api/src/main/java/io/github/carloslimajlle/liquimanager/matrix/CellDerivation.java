package io.github.carloslimajlle.liquimanager.matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the stored state of a changeset in each pipeline stage into what the Deployment
 * Matrix shows (docs/telas/04).
 *
 * <p>What is stored says what is KNOWN about each database: it ran, it failed, it was
 * rolled back, it was filtered out. What is NOT stored is the interesting part of a
 * promotion pipeline — whether a changeset is waiting for its turn or has not even
 * reached that environment yet. That distinction is derived here, walking the stages
 * in order:
 *
 * <ul>
 *   <li>{@code PENDING}: not applied, and everything before it is fine. It is the NEXT stop.
 *   <li>{@code NOT_REACHED}: not applied, and an earlier stage is not fine either. Shown as "—".
 * </ul>
 *
 * <p>"Fine" means the previous stage ran it, or was never meant to (filtered out by
 * context). A failure or a rollback is NOT fine: nothing after it can be pending.
 *
 * <p>Pure on purpose, with no database and no Spring, so every branch is cheap to test.
 */
public final class CellDerivation {

    public static final String EXECUTED = "EXECUTED";
    public static final String FAILED = "FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String PENDING = "PENDING";
    public static final String NOT_TARGETED = "NOT_TARGETED";
    public static final String OUT_OF_PROCESS = "OUT_OF_PROCESS";
    public static final String NOT_REACHED = "NOT_REACHED";

    private CellDerivation() {
    }

    /**
     * @param stored the stored status per stage, in pipeline order; null when nothing is
     *               known about that stage. A stored {@code PENDING} means "an operation
     *               saw it but did not run it" and is treated like "nothing known".
     */
    public static List<String> derive(List<String> stored) {
        List<String> derived = new ArrayList<>(stored.size());
        boolean ready = true;

        for (String status : stored) {
            if (status == null || PENDING.equals(status)) {
                derived.add(ready ? PENDING : NOT_REACHED);
                // Whatever sits after this stage has not had its turn: this one did not either.
                ready = false;
                continue;
            }
            switch (status) {
                case EXECUTED, OUT_OF_PROCESS -> {
                    derived.add(status);
                    ready = true;
                }
                case FAILED, ROLLED_BACK -> {
                    derived.add(status);
                    ready = false;
                }
                case NOT_TARGETED -> derived.add(NOT_TARGETED); // does not change readiness
                default -> {
                    derived.add(status);
                    ready = false;
                }
            }
        }
        return derived;
    }

    /** In sync: every stage either ran it or was never meant to. */
    public static boolean inSync(List<String> derived) {
        return derived.stream().allMatch(s -> EXECUTED.equals(s) || NOT_TARGETED.equals(s));
    }

    /** Waiting on a promotion: some stage has it as the next stop. */
    public static boolean awaitingPromotion(List<String> derived) {
        return derived.contains(PENDING);
    }

    /** Index of the furthest stage holding a pending cell, or -1. */
    public static int mostAdvancedPending(List<String> derived) {
        return derived.lastIndexOf(PENDING);
    }
}
