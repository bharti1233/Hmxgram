package com.feurstagram.extension.downloader.core;

/**
 * M12: every placement rejection branch, tested without Android.
 *
 * <p>The Android layer still places the control exactly as M11 did. These
 * tests prove the extractable policy that M13 will adopt only after real
 * device evidence says which branch actually fires.
 *
 * <p>Pure JDK: no Android class, no device.
 */
public final class PlacementPolicyTest {

    private static final int ICON = 40;

    private PlacementPolicyTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M12 test group and returns the number of failures. */
    public static int run() {
        int failures = 0;
        failures += group("feed rejects a missing parent", new Runnable() {
            @Override public void run() { feedRejectsMissingParent(); }
        });
        failures += group("feed rejects an invisible parent", new Runnable() {
            @Override public void run() { feedRejectsInvisibleParent(); }
        });
        failures += group("feed rejects a missing bookmark", new Runnable() {
            @Override public void run() { feedRejectsMissingBookmark(); }
        });
        failures += group("feed rejects a non-horizontal parent", new Runnable() {
            @Override public void run() { feedRejectsVerticalParent(); }
        });
        failures += group("feed rejects a right-to-left parent", new Runnable() {
            @Override public void run() { feedRejectsRightToLeft(); }
        });
        failures += group("feed rejects an unordered parent", new Runnable() {
            @Override public void run() { feedRejectsUnorderedParent(); }
        });
        failures += group("feed rejects hidden or detached bookmarks", new Runnable() {
            @Override public void run() { feedRejectsHiddenBookmark(); }
        });
        failures += group("feed rejects unmeasured geometry", new Runnable() {
            @Override public void run() { feedRejectsUnmeasuredGeometry(); }
        });
        failures += group("feed accepts a complete row", new Runnable() {
            @Override public void run() { feedAcceptsCompleteRow(); }
        });
        failures += group("reels rejects a missing bar", new Runnable() {
            @Override public void run() { reelsRejectsMissingBar(); }
        });
        failures += group("reels rejects hidden or detached bars", new Runnable() {
            @Override public void run() { reelsRejectsHiddenBar(); }
        });
        failures += group("reels rejects unmeasured geometry", new Runnable() {
            @Override public void run() { reelsRejectsUnmeasured(); }
        });
        failures += group("reels detects overlap at both anchors", new Runnable() {
            @Override public void run() { reelsDetectsOverlap(); }
        });
        failures += group("reels prefers the upper-right slot", new Runnable() {
            @Override public void run() { reelsPrefersUpperRight(); }
        });
        failures += group("reels falls back to the centre slot", new Runnable() {
            @Override public void run() { reelsFallsBackToCentre(); }
        });
        failures += group("reels ignores transparent wrappers but not their content",
                new Runnable() {
                    @Override public void run() { reelsIgnoresTransparentWrapper(); }
                });
        failures += group("reels ignores hidden and transparent subtrees", new Runnable() {
            @Override public void run() { reelsIgnoresHiddenSubtree(); }
        });
        failures += group("reels refuses a container it cannot lay out", new Runnable() {
            @Override public void run() { reelsRefusesUnknownContainer(); }
        });
        failures += group("reels accepts the proven container kinds", new Runnable() {
            @Override public void run() { reelsAcceptsProvenContainers(); }
        });
        failures += group("decisions are total and deterministic", new Runnable() {
            @Override public void run() { decisionsAreTotal(); }
        });
        System.out.println(failures == 0 ? "PASS placement policy"
                : "FAIL placement policy (" + failures + ")");
        return failures;
    }

    private static void feedRejectsMissingParent() {
        equal(PlacementPolicy.Reason.PARENT_MISSING,
                PlacementPolicy.feed(null, bookmark().build(), ICON).reason());
    }

    private static void feedRejectsInvisibleParent() {
        PlacementPolicy.Node parent = row().visible(false).build();
        equal(PlacementPolicy.Reason.PARENT_INVISIBLE,
                PlacementPolicy.feed(parent, bookmark().build(), ICON).reason());
    }

    private static void feedRejectsMissingBookmark() {
        PlacementPolicy.Node parent = row().build();
        equal(PlacementPolicy.Reason.BOOKMARK_MISSING,
                PlacementPolicy.feed(parent, null, ICON).reason());
    }

    private static void feedRejectsVerticalParent() {
        PlacementPolicy.Node parent = row().axis(PlacementPolicy.Axis.VERTICAL).build();
        equal(PlacementPolicy.Reason.PARENT_VERTICAL,
                PlacementPolicy.feed(parent, bookmark().build(), ICON).reason());
    }

    private static void feedRejectsRightToLeft() {
        PlacementPolicy.Node parent = row().leftToRight(false).build();
        equal(PlacementPolicy.Reason.PARENT_RTL,
                PlacementPolicy.feed(parent, bookmark().build(), ICON).reason());
    }

    private static void feedRejectsUnorderedParent() {
        PlacementPolicy.Node parent = row().orderedChildren(false).build();
        equal(PlacementPolicy.Reason.PARENT_NOT_ORDERED,
                PlacementPolicy.feed(parent, bookmark().build(), ICON).reason());
    }

    private static void feedRejectsHiddenBookmark() {
        PlacementPolicy.Node parent = row().build();
        equal(PlacementPolicy.Reason.BOOKMARK_INVISIBLE,
                PlacementPolicy.feed(parent, bookmark().visible(false).build(), ICON)
                        .reason());
        equal(PlacementPolicy.Reason.BOOKMARK_DETACHED,
                PlacementPolicy.feed(parent, bookmark().attached(false).build(), ICON)
                        .reason());
    }

    private static void feedRejectsUnmeasuredGeometry() {
        PlacementPolicy.Node parent = row().size(0, 48).build();
        equal(PlacementPolicy.Reason.PARENT_NOT_MEASURED,
                PlacementPolicy.feed(parent, bookmark().build(), ICON).reason());
        PlacementPolicy.Node measured = row().build();
        equal(PlacementPolicy.Reason.ICON_UNMEASURED,
                PlacementPolicy.feed(measured, bookmark().build(), 0).reason());
        PlacementPolicy.Node narrow = row().size(30, 48).build();
        equal(PlacementPolicy.Reason.NO_HORIZONTAL_ROOM,
                PlacementPolicy.feed(narrow, bookmark().build(), ICON).reason());
    }

    private static void feedAcceptsCompleteRow() {
        PlacementPolicy.Result exact = PlacementPolicy.feed(
                row().size(ICON, 48).build(), bookmark().build(), ICON);
        check(exact.placed(), "a row exactly one icon wide still places");
        equal(PlacementPolicy.Reason.NONE, exact.reason());

        PlacementPolicy.Result padded = PlacementPolicy.feed(
                row().size(ICON + 12, 48).padding(6, 0, 6, 0).build(),
                bookmark().build(), ICON);
        check(padded.placed(), "a row one icon wide inside its padding places");
    }

    private static void reelsRejectsMissingBar() {
        equal(PlacementPolicy.Reason.BAR_MISSING,
                PlacementPolicy.reels(null, ICON, ICON).reason());
    }

    private static void reelsRejectsHiddenBar() {
        equal(PlacementPolicy.Reason.BAR_INVISIBLE,
                PlacementPolicy.reels(bar().visible(false).build(), ICON, ICON).reason());
        equal(PlacementPolicy.Reason.BAR_DETACHED,
                PlacementPolicy.reels(bar().attached(false).build(), ICON, ICON).reason());
    }

    private static void reelsRejectsUnmeasured() {
        equal(PlacementPolicy.Reason.ICON_UNMEASURED,
                PlacementPolicy.reels(bar().build(), 0, ICON).reason());
        equal(PlacementPolicy.Reason.BAR_NOT_MEASURED,
                PlacementPolicy.reels(bar().size(0, 0).build(), ICON, ICON).reason());
        equal(PlacementPolicy.Reason.OUT_OF_BOUNDS,
                PlacementPolicy.reels(bar().size(30, 30).build(), ICON, ICON).reason());
    }

    private static void reelsDetectsOverlap() {
        PlacementPolicy.Node full = bar()
                .child(PlacementPolicy.node().type("FullWidth").at(0, 0)
                        .size(200, 100).build())
                .build();
        PlacementPolicy.Result result = PlacementPolicy.reels(full, ICON, ICON);
        check(!result.placed(), "a full-width control blocks both anchors");
        equal(PlacementPolicy.Reason.OVERLAP, result.reason());
        equal("FullWidth", result.blocker());
    }

    private static void reelsPrefersUpperRight() {
        PlacementPolicy.Result result = PlacementPolicy.reels(
                bar().child(PlacementPolicy.node().type("Left").at(0, 0)
                        .size(100, 100).blocks(true).build()).build(),
                ICON, ICON);
        check(result.placed(), "the upper-right slot is free");
        equal(PlacementPolicy.Anchor.END_TOP, result.anchor());
    }

    private static void reelsFallsBackToCentre() {
        PlacementPolicy.Node topBlocked = bar()
                .child(PlacementPolicy.node().type("TopBar").at(0, 0)
                        .size(200, 30).blocks(true).build())
                .build();
        PlacementPolicy.Result result = PlacementPolicy.reels(topBlocked, ICON, ICON);
        check(result.placed(), "the centre slot is still free");
        equal(PlacementPolicy.Anchor.END_CENTER, result.anchor());

        PlacementPolicy.Node centreBlocked = bar()
                .child(PlacementPolicy.node().type("CentreBar").at(0, 60)
                        .size(200, 40).blocks(true).build())
                .build();
        equal(PlacementPolicy.Anchor.END_TOP,
                PlacementPolicy.reels(centreBlocked, ICON, ICON).anchor());
    }

    private static void reelsIgnoresTransparentWrapper() {
        PlacementPolicy.Node wrapper = PlacementPolicy.node().type("Wrapper")
                .at(0, 0).size(200, 100)
                .child(PlacementPolicy.node().type("RealTop").at(0, 0)
                        .size(200, 30).blocks(true).build())
                .build();
        PlacementPolicy.Result result = PlacementPolicy.reels(
                bar().child(wrapper).build(), ICON, ICON);
        check(result.placed(), "the wrapper itself does not block the slot");
        equal(PlacementPolicy.Anchor.END_CENTER, result.anchor());
    }

    private static void reelsIgnoresHiddenSubtree() {
        PlacementPolicy.Node hidden = PlacementPolicy.node().type("Hidden")
                .at(0, 0).size(200, 100).visible(false)
                .child(PlacementPolicy.node().type("Inner").at(0, 0)
                        .size(200, 100).blocks(true).build())
                .build();
        PlacementPolicy.Result result = PlacementPolicy.reels(
                bar().child(hidden).build(), ICON, ICON);
        check(result.placed(), "a hidden container hides its children");
        equal(PlacementPolicy.Anchor.END_TOP, result.anchor());

        PlacementPolicy.Node transparent = PlacementPolicy.node().type("Faded")
                .at(0, 0).size(200, 100).alpha(0f).blocks(true).build();
        check(PlacementPolicy.reels(bar().child(transparent).build(), ICON, ICON)
                .placed(), "a fully transparent control does not block");
    }

    private static void reelsRefusesUnknownContainer() {
        equal(PlacementPolicy.Reason.ANCHOR_TYPE,
                PlacementPolicy.reels(bar().build(), ICON, ICON,
                        PlacementPolicy.Container.UNSUPPORTED).reason());
        equal(PlacementPolicy.Reason.ANCHOR_TYPE,
                PlacementPolicy.reels(bar().build(), ICON, ICON, null).reason());
        check(!PlacementPolicy.reels(bar().build(), ICON, ICON,
                        PlacementPolicy.Container.UNSUPPORTED).placed(),
                "an unsupported container must never place");
    }

    private static void reelsAcceptsProvenContainers() {
        PlacementPolicy.Result frame = PlacementPolicy.reels(
                bar().build(), ICON, ICON, PlacementPolicy.Container.FRAME);
        check(frame.placed(), "a FrameLayout bar may host the control");
        equal(PlacementPolicy.Anchor.END_TOP, frame.anchor());

        PlacementPolicy.Result relative = PlacementPolicy.reels(
                bar().build(), ICON, ICON, PlacementPolicy.Container.RELATIVE);
        check(relative.placed(), "a RelativeLayout bar may host the control");
        equal(PlacementPolicy.Anchor.END_TOP, relative.anchor());

        PlacementPolicy.Node blocked = bar()
                .child(PlacementPolicy.node().type("FullWidth").at(0, 0)
                        .size(200, 100).blocks(true).build())
                .build();
        PlacementPolicy.Result overlap = PlacementPolicy.reels(
                blocked, ICON, ICON, PlacementPolicy.Container.RELATIVE);
        check(!overlap.placed(), "a RelativeLayout bar still needs a free slot");
        equal(PlacementPolicy.Reason.OVERLAP, overlap.reason());
    }

    private static void decisionsAreTotal() {
        PlacementPolicy.Axis[] axes = PlacementPolicy.Axis.values();
        for (int mask = 0; mask < 64; mask++) {
            for (PlacementPolicy.Axis axis : axes) {
                for (int icon : new int[] {-1, 0, 1, ICON}) {
                    PlacementPolicy.Node parent = row()
                            .axis(axis)
                            .leftToRight((mask & 1) != 0)
                            .orderedChildren((mask & 2) != 0)
                            .visible((mask & 4) != 0)
                            .attached((mask & 8) != 0)
                            .size((mask & 16) != 0 ? 200 : 0, 48)
                            .build();
                    PlacementPolicy.Node mark = bookmark()
                            .visible((mask & 32) != 0).build();
                    PlacementPolicy.Result result =
                            PlacementPolicy.feed(parent, mark, icon);
                    check(result != null, "feed always yields a result");
                    check(!result.code().isEmpty(), "feed always has a code");
                    if (result.placed()) {
                        check(result.reason() == PlacementPolicy.Reason.NONE,
                                "a placement has no refusal reason");
                    } else {
                        check(result.reason() != PlacementPolicy.Reason.NONE,
                                "a refusal always has a reason");
                        check(result.anchor() == PlacementPolicy.Anchor.NONE,
                                "a refusal has no anchor");
                    }
                    PlacementPolicy.Result again =
                            PlacementPolicy.feed(parent, mark, icon);
                    equal(result.code(), again.code());
                }
            }
        }
        for (int width : new int[] {-1, 0, 10, 200}) {
            for (int height : new int[] {-1, 0, 10, 100}) {
                for (PlacementPolicy.Container container
                        : PlacementPolicy.Container.values()) {
                    PlacementPolicy.Result result = PlacementPolicy.reels(
                            bar().size(width, height).build(), ICON, ICON, container);
                    check(result != null, "reels always yields a result");
                    if (!result.placed()) {
                        check(result.reason() != PlacementPolicy.Reason.NONE,
                                "a Reels refusal always has a reason");
                    }
                }
            }
        }
    }

    // --- fixtures -------------------------------------------------------

    private static PlacementPolicy.Builder row() {
        return PlacementPolicy.node().type("Row")
                .axis(PlacementPolicy.Axis.HORIZONTAL)
                .leftToRight(true)
                .orderedChildren(true)
                .size(200, 48);
    }

    private static PlacementPolicy.Builder bookmark() {
        return PlacementPolicy.node().type("Bookmark")
                .axis(PlacementPolicy.Axis.NONE)
                .size(24, 24);
    }

    private static PlacementPolicy.Builder bar() {
        return PlacementPolicy.node().type("Bar")
                .axis(PlacementPolicy.Axis.NONE)
                .orderedChildren(false)
                .size(200, 100);
    }

    // --- harness --------------------------------------------------------

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

    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
