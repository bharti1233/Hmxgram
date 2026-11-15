package com.feurstagram.extension.downloader.core;

/**
 * M7.1 regression tests: the in-context chip must never be forced into a
 * window that is not ready, and a window that never becomes ready must be
 * abandoned quietly instead of crashing the app.
 *
 * <p>Pure JDK: no Android class, no device.
 */
public final class OverlayLifecyclePolicyTest {

    private static final int MAX = 20;

    private OverlayLifecyclePolicyTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M7.1 test group and returns the number of failures. */
    public static int run() {
        int failures = 0;
        failures += group("attach needs every prerequisite", new Runnable() {
            @Override
            public void run() {
                attachNeedsEveryPrerequisite();
            }
        });
        failures += group("unready window waits then abandons", new Runnable() {
            @Override
            public void run() {
                unreadyWindowWaitsThenAbandons();
            }
        });
        failures += group("degenerate limits never attach", new Runnable() {
            @Override
            public void run() {
                degenerateLimitsNeverAttach();
            }
        });
        failures += group("decision is total", new Runnable() {
            @Override
            public void run() {
                decisionIsTotal();
            }
        });
        System.out.println(failures == 0 ? "PASS overlay lifecycle"
                : "FAIL overlay lifecycle (" + failures + ")");
        return failures;
    }

    private static void attachNeedsEveryPrerequisite() {
        check(OverlayLifecyclePolicy.decide(true, true, true, 0, MAX)
                == OverlayLifecyclePolicy.Decision.ATTACH, "a ready window attaches");
        check(OverlayLifecyclePolicy.decide(false, true, true, 0, MAX)
                == OverlayLifecyclePolicy.Decision.WAIT, "an unattached bar waits");
        check(OverlayLifecyclePolicy.decide(true, false, true, 0, MAX)
                == OverlayLifecyclePolicy.Decision.WAIT, "a missing window root waits");
        check(OverlayLifecyclePolicy.decide(true, true, false, 0, MAX)
                == OverlayLifecyclePolicy.Decision.WAIT,
                "a missing Activity context waits instead of using the app context");
    }

    private static void unreadyWindowWaitsThenAbandons() {
        check(OverlayLifecyclePolicy.decide(false, false, false, 0, MAX)
                == OverlayLifecyclePolicy.Decision.WAIT, "the first attempt waits");
        check(OverlayLifecyclePolicy.decide(false, false, false, MAX - 2, MAX)
                == OverlayLifecyclePolicy.Decision.WAIT, "the last allowed attempt still waits");
        check(OverlayLifecyclePolicy.decide(false, false, false, MAX - 1, MAX)
                == OverlayLifecyclePolicy.Decision.ABANDON, "attempts are bounded");
        check(OverlayLifecyclePolicy.decide(false, false, false, MAX, MAX)
                == OverlayLifecyclePolicy.Decision.ABANDON, "past the bound is abandoned");
        check(OverlayLifecyclePolicy.decide(false, false, false, -7, MAX)
                == OverlayLifecyclePolicy.Decision.WAIT, "a negative attempt is treated as the first");
        check(OverlayLifecyclePolicy.decide(true, true, true, MAX, MAX)
                == OverlayLifecyclePolicy.Decision.ATTACH,
                "a ready window still attaches on a late attempt");
    }

    private static void degenerateLimitsNeverAttach() {
        check(OverlayLifecyclePolicy.decide(true, true, true, 0, 0)
                == OverlayLifecyclePolicy.Decision.ABANDON, "zero attempts abandons");
        check(OverlayLifecyclePolicy.decide(true, true, true, 0, -3)
                == OverlayLifecyclePolicy.Decision.ABANDON, "a negative limit abandons");
    }

    private static void decisionIsTotal() {
        for (int attempt = -5; attempt <= 25; attempt++) {
            for (int max = -2; max <= 25; max++) {
                for (int mask = 0; mask < 8; mask++) {
                    OverlayLifecyclePolicy.Decision decision = OverlayLifecyclePolicy.decide(
                            (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, attempt, max);
                    check(decision != null, "every input yields a decision");
                    if (decision == OverlayLifecyclePolicy.Decision.ATTACH) {
                        check((mask & 7) == 7, "attach is only possible with all prerequisites");
                        check(max > 0, "attach is only possible with attempts left");
                    }
                }
            }
        }
    }

    private static int group(String name, Runnable body) {
        try {
            body.run();
            System.out.println("PASS " + name);
            return 0;
        } catch (AssertionError failure) {
            System.out.println("FAIL " + name + ": " + failure.getMessage());
            return 1;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
