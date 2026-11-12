import com.feurstagram.extension.ghost.GhostFeature;
import com.feurstagram.extension.ghost.GhostPolicy;

import java.util.EnumMap;
import java.util.Map;

/**
 * Offline tests for the ghost-mode rules: what the master switch controls and
 * which requests are suppressed.
 *
 * Run by scripts/ci/test_ghost_mode.sh with plain javac: no Android device and
 * no emulator is involved.
 */
public final class GhostModeTest {

    private static int checks = 0;

    public static void main(String[] args) {
        masterSwitch();
        unsupportedFeatures();
        statusLabels();
        storySeenSuppression();
        messageSeenSuppression();
        typingSuppression();
        unrelatedRequests();

        System.out.println("Ghost mode tests passed (" + checks + " checks)");
    }

    private static void masterSwitch() {
        check("master off: nothing active",
                !GhostPolicy.isActive(GhostFeature.HIDE_STORY_SEEN, false, true));
        check("master off: typing not active",
                !GhostPolicy.isActive(GhostFeature.HIDE_TYPING_STATUS, false, true));
        check("master on: enabled feature active",
                GhostPolicy.isActive(GhostFeature.HIDE_STORY_SEEN, true, true));
        check("master on: disabled feature inactive",
                !GhostPolicy.isActive(GhostFeature.HIDE_STORY_SEEN, true, false));
        check("master on: message seen active",
                GhostPolicy.isActive(GhostFeature.HIDE_MESSAGE_SEEN, true, true));

        Map<GhostFeature, Boolean> preferences = new EnumMap<>(GhostFeature.class);
        preferences.put(GhostFeature.HIDE_STORY_SEEN, true);
        preferences.put(GhostFeature.HIDE_MESSAGE_SEEN, false);
        Map<GhostFeature, Boolean> active = GhostPolicy.activeControls(true, preferences);
        check("active map: story seen on", Boolean.TRUE.equals(active.get(GhostFeature.HIDE_STORY_SEEN)));
        check("active map: message seen off", !Boolean.TRUE.equals(active.get(GhostFeature.HIDE_MESSAGE_SEEN)));
        check("active map: master off empties it",
                !Boolean.TRUE.equals(GhostPolicy.activeControls(false, preferences)
                        .get(GhostFeature.HIDE_STORY_SEEN)));
    }

    private static void unsupportedFeatures() {
        GhostFeature[] unsupported = {
                GhostFeature.HIDE_ONLINE_STATUS,
                GhostFeature.DISABLE_ACTIVITY_STATUS,
                GhostFeature.ANONYMOUS_STORY_VIEWING,
                GhostFeature.HIDE_LIVE_VIEW,
        };
        for (GhostFeature feature : unsupported) {
            check(feature.key() + ": never active",
                    !GhostPolicy.isActive(feature, true, true));
            check(feature.key() + ": not controllable", !feature.isControllable());
            check(feature.key() + ": has an explanation", feature.explanation() != null
                    && feature.explanation().length() > 20);
        }
        check("supported features are controllable",
                GhostFeature.HIDE_STORY_SEEN.isControllable()
                        && GhostFeature.HIDE_MESSAGE_SEEN.isControllable()
                        && GhostFeature.HIDE_TYPING_STATUS.isControllable());
    }

    private static void statusLabels() {
        check("supported label", "Supported".equals(GhostFeature.HIDE_STORY_SEEN.statusLabel()));
        check("unsupported label",
                "Not supported".equals(GhostFeature.ANONYMOUS_STORY_VIEWING.statusLabel()));
    }

    private static void storySeenSuppression() {
        String path = "/api/v1/media/3123456789012345678/seen/";
        check("story seen: suppressed when on",
                GhostPolicy.shouldSuppress(path, true, false, false));
        check("story seen: not suppressed when off",
                !GhostPolicy.shouldSuppress(path, false, false, false));
        check("story seen: recognised", GhostPolicy.isStorySeen(path));
        check("story seen: other media path is not a receipt",
                !GhostPolicy.isStorySeen("/api/v1/media/3123456789012345678/info/"));
    }

    private static void messageSeenSuppression() {
        String path = "/api/v1/direct_v2/threads/12345/items/67890/seen/";
        check("message seen: suppressed when on",
                GhostPolicy.shouldSuppress(path, false, true, false));
        check("message seen: not suppressed when off",
                !GhostPolicy.shouldSuppress(path, false, false, false));
    }

    private static void typingSuppression() {
        String path = "/api/v1/direct_v2/threads/12345/items/67890/activity/";
        check("typing: suppressed when on",
                GhostPolicy.shouldSuppress(path, false, false, true));
        check("typing: not suppressed when off",
                !GhostPolicy.shouldSuppress(path, false, false, false));
    }

    private static void unrelatedRequests() {
        String[] untouched = {
                "/api/v1/feed/timeline/",
                "/api/v1/media/3123456789012345678/info/",
                "/api/v1/feed/reels_tray/",
                "/api/v1/users/1/info/",
                "",
        };
        for (String path : untouched) {
            check("untouched: " + path,
                    !GhostPolicy.shouldSuppress(path, true, true, true));
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("FAILED: " + what);
    }
}
