package com.feurstagram.extension.ghost;

/**
 * M5 tests: the privacy catalogue and the rules that decide whether a
 * control is in effect.
 *
 * <p>The point of these tests is honesty: a control that cannot work must be
 * reported as unsupported, must never be switchable, and must never be
 * active, whatever the preferences say.
 */
public final class GhostPolicyTest {

    private GhostPolicyTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M5 test group and returns the number of failures. */
    public static int run() {
        int failures = 0;
        failures += group("catalogue", new Runnable() {
            @Override
            public void run() {
                catalogue();
            }
        });
        failures += group("policy", new Runnable() {
            @Override
            public void run() {
                policy();
            }
        });
        failures += group("unsupported controls", new Runnable() {
            @Override
            public void run() {
                unsupportedControls();
            }
        });
        failures += group("supported controls first", new Runnable() {
            @Override
            public void run() {
                supportedFirst();
            }
        });
        failures += group("master gating", new Runnable() {
            @Override
            public void run() {
                masterGating();
            }
        });
        if (failures == 0) {
            System.out.println("PASS 5 ghost-policy tests");
        }
        return failures;
    }

    private static int group(String name, Runnable test) {
        try {
            test.run();
            System.out.println("PASS " + name);
            return 0;
        } catch (Throwable failure) {
            System.out.println("FAIL " + name + ": " + failure);
            return 1;
        }
    }

    private static void catalogue() {
        GhostFeature[] all = GhostFeature.all();
        check(all.length == 9, "the catalogue lists every control");
        for (GhostFeature feature : all) {
            check(feature.id() != null && !feature.id().trim().isEmpty(),
                    "every control has an id");
            check(feature.title() != null && !feature.title().trim().isEmpty(),
                    "every control has a title");
            check(feature.explanation() != null
                    && !feature.explanation().trim().isEmpty(),
                    "every control explains what it does");
            check(feature.preferenceKey().startsWith("hmx_ghost_"),
                    "every control has its own preference key");
            check(GhostFeature.byId(feature.id()) == feature,
                    "every control is found by its id");
        }
        check(GhostFeature.byId("nope") == null, "an unknown id finds nothing");
        check(GhostFeature.byId(null) == null, "a null id finds nothing");
    }

    private static void policy() {
        GhostFeature supported = GhostFeature.CONFIRM_BEFORE_SAVING;
        check(supported.isSupported(), "the local controls are supported");
        check(GhostPolicy.canBeSwitched(supported), "a supported control can be switched");

        check(GhostPolicy.isActive(supported, true, true),
                "a supported control is active when both switches are on");
        check(!GhostPolicy.isActive(supported, false, true),
                "the master switch turns a control off");
        check(!GhostPolicy.isActive(supported, true, false),
                "the control's own switch turns it off");
        check(!GhostPolicy.isActive(supported, false, false),
                "both switches off means off");

        check(GhostPolicy.effective(true, true), "the effective state needs both");
        check(!GhostPolicy.effective(false, true), "the master switch gates the state");
        check(!GhostPolicy.effective(true, false), "the own switch gates the state");

        try {
            GhostPolicy.isActive(null, true, true);
            throw new AssertionError("a null control must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            GhostPolicy.canBeSwitched(null);
            throw new AssertionError("a null control must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void unsupportedControls() {
        GhostFeature[] instagramFacing = {
                GhostFeature.HIDE_STORY_SEEN,
                GhostFeature.HIDE_MESSAGE_SEEN,
                GhostFeature.HIDE_TYPING_STATUS,
                GhostFeature.HIDE_ONLINE_STATUS,
                GhostFeature.DISABLE_ACTIVITY_STATUS,
                GhostFeature.ANONYMOUS_STORY_VIEWING,
                GhostFeature.HIDE_LIVE_VIEW,
        };
        for (GhostFeature feature : instagramFacing) {
            check(!feature.isSupported(),
                    feature.id() + " must not claim to work");
            check(GhostFeature.Support.UNSUPPORTED == feature.support(),
                    feature.id() + " is unsupported");
            check(feature.unsupportedReason() != null
                    && feature.unsupportedReason().trim().length() > 20,
                    feature.id() + " explains why it cannot work");
            check(!GhostPolicy.canBeSwitched(feature),
                    feature.id() + " cannot be switched on");
            check(!GhostPolicy.mayEnable(feature),
                    feature.id() + " may not be enabled");
            check(!GhostPolicy.isActive(feature, true, true),
                    feature.id() + " is never active, even with both switches on");
        }
    }

    private static void supportedFirst() {
        GhostFeature[] ordered = GhostFeature.supportedFirst();
        check(ordered.length == GhostFeature.all().length,
                "ordering keeps every control");
        boolean seenUnsupported = false;
        int supported = 0;
        for (GhostFeature feature : ordered) {
            if (feature.isSupported()) {
                check(!seenUnsupported,
                        "a supported control must not appear after an unsupported one");
                supported++;
            } else {
                seenUnsupported = true;
            }
        }
        check(supported == 2, "both supported controls are in the first group");
        check(ordered[0].isSupported() && ordered[1].isSupported(),
                "the two supported switches are the first two rows");
    }

    /**
     * The master switch is the card's switch: off keeps every supported
     * control inactive, on restores the stored choices, and no unsupported
     * control can ever become active.
     */
    private static void masterGating() {
        GhostFeature[] supported = {
                GhostFeature.CONFIRM_BEFORE_SAVING,
                GhostFeature.HIDE_DOWNLOAD_HISTORY,
        };
        for (GhostFeature feature : supported) {
            check(feature.isSupported(), feature.id() + " is supported");
            check(!GhostPolicy.isActive(feature, false, true),
                    "master off disables " + feature.id());
            check(GhostPolicy.isActive(feature, true, true),
                    "master on restores " + feature.id());
        }
        check(!GhostPolicy.isActive(GhostFeature.HIDE_STORY_SEEN, true, true),
                "the master switch cannot enable an unsupported control");
        check(!GhostPolicy.isActive(GhostFeature.HIDE_MESSAGE_SEEN, true, true),
                "the master switch cannot enable another unsupported control");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
