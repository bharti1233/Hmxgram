package com.feurstagram.extension.downloader.core;

import java.util.Arrays;
import java.util.List;

/**
 * M7 tests: the in-context action's freshness, target and wording rules.
 *
 * <p>Pure JDK: no Android class, no device. The same code decides at runtime
 * whether the chip is offered, so these tests are the behaviour gate for it.
 */
public final class InContextActionTest {

    private InContextActionTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M7 test group and returns the number of failures. */
    public static int run() {
        int failures = 0;
        failures += group("freshness and surface", new Runnable() {
            @Override
            public void run() {
                freshnessAndSurface();
            }
        });
        failures += group("carousel targets", new Runnable() {
            @Override
            public void run() {
                carouselTargets();
            }
        });
        failures += group("single targets", new Runnable() {
            @Override
            public void run() {
                singleTargets();
            }
        });
        failures += group("titles and details", new Runnable() {
            @Override
            public void run() {
                titlesAndDetails();
            }
        });
        failures += group("result messages", new Runnable() {
            @Override
            public void run() {
                resultMessages();
            }
        });
        failures += group("age labels", new Runnable() {
            @Override
            public void run() {
                ageLabels();
            }
        });
        if (failures == 0) {
            System.out.println("PASS 6 in-context action tests");
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

    private static void freshnessAndSurface() {
        ObservedMedia photo = photo("p1", "https://cdn.example/p1.jpg");
        check(InContextAction.canOffer(photo, 0L, InContextAction.Surface.FEED),
                "a fresh photo is offered");
        check(InContextAction.canOffer(photo, InContextAction.MAX_AGE_MS,
                InContextAction.Surface.REELS), "the age boundary is inclusive");
        check(!InContextAction.canOffer(photo, InContextAction.MAX_AGE_MS + 1L,
                InContextAction.Surface.FEED), "a stale observation is not offered");
        check(!InContextAction.canOffer(photo, -1L, InContextAction.Surface.FEED),
                "a negative age is not trusted");
        check(!InContextAction.canOffer(null, 0L, InContextAction.Surface.FEED),
                "no observation means nothing to offer");
        check(!InContextAction.canOffer(photo, 0L, null),
                "an unrecognised surface is not offered");
        check(InContextAction.canOffer(photo, 0L, InContextAction.Surface.STORIES),
                "a supported story media is offered");
    }

    private static void carouselTargets() {
        ObservedMedia usablePhoto = photo("c1", "https://cdn.example/c1.jpg");
        ObservedMedia usableVideo = video("c2", "https://cdn.example/c2.mp4", true);
        ObservedMedia unusable = ObservedMedia.single("c3", DownloadKind.PHOTO, null,
                null, "alice", 0, 0, 0L, 0L, false);
        ObservedMedia carousel = ObservedMedia.carousel("car",
                Arrays.asList(usablePhoto, usableVideo, unusable), "alice", 0L);

        List<ObservedMedia> targets = InContextAction.targets(carousel);
        check(targets.size() == 2, "the carousel expands to its usable children");
        check(targets.get(0) == usablePhoto, "the first child keeps its order");
        check(targets.get(1) == usableVideo, "the video child is kept");
        check(!targets.contains(unusable), "a child without a URL is skipped");
        check(InContextAction.skippedChildren(carousel) == 1,
                "the skipped child is counted");
        check(InContextAction.skippedChildren(usablePhoto) == 0,
                "a single media has no skipped children");

        ObservedMedia empty = ObservedMedia.carousel("empty",
                Arrays.asList(unusable), "alice", 0L);
        check(InContextAction.targets(empty).isEmpty(),
                "a carousel with no usable child has no targets");
        check(!InContextAction.canOffer(empty, 0L, InContextAction.Surface.FEED),
                "a carousel with no usable child is not offered");
    }

    private static void singleTargets() {
        ObservedMedia photo = photo("p2", "https://cdn.example/p2.jpg");
        check(InContextAction.targets(photo).size() == 1,
                "a single photo is its own target");
        ObservedMedia noUrl = ObservedMedia.single("p3", DownloadKind.PHOTO, null,
                "image/jpeg", "alice", 0, 0, 0L, 0L, false);
        check(InContextAction.targets(noUrl).isEmpty(),
                "a photo without a URL has no target");
        ObservedMedia notHttp = ObservedMedia.single("p4", DownloadKind.PHOTO,
                "file:///tmp/p4.jpg", "image/jpeg", "alice", 0, 0, 0L, 0L, false);
        check(InContextAction.targets(notHttp).isEmpty(),
                "only http(s) URLs are handed to the engine");
        check(InContextAction.targets(null).isEmpty(),
                "a null observation has no target");
    }

    private static void titlesAndDetails() {
        check("Photo by alice".equals(InContextAction.title(
                photo("p5", "https://cdn.example/p5.jpg"))), "a photo names its owner");
        check("Photo".equals(InContextAction.title(ObservedMedia.single("p6",
                DownloadKind.PHOTO, "https://cdn.example/p6.jpg", "image/jpeg",
                "instagram", 0, 0, 0L, 0L, false))),
                "the placeholder owner is not shown");
        check("Video with audio by alice".equals(InContextAction.title(
                video("v1", "https://cdn.example/v1.mp4", true))),
                "a video with audio says so");
        check("Video by alice".equals(InContextAction.title(
                video("v2", "https://cdn.example/v2.mp4", false))),
                "a muted video does not claim audio");

        ObservedMedia carousel = ObservedMedia.carousel("car2",
                Arrays.asList(photo("d1", "https://cdn.example/d1.jpg"),
                        video("d2", "https://cdn.example/d2.mp4", false)),
                "alice", 0L);
        check("Carousel · 2 items by alice".equals(InContextAction.title(carousel)),
                "a carousel names its usable item count");

        String story = InContextAction.detail(
                photo("p7", "https://cdn.example/p7.jpg"), 4000L,
                InContextAction.Surface.STORIES);
        check(story.contains("4s ago"), "the detail carries the age");
        check(story.contains("private or expired stories cannot be downloaded"),
                "a story detail states its limit");
        String feed = InContextAction.detail(
                photo("p8", "https://cdn.example/p8.jpg"), 0L,
                InContextAction.Surface.FEED);
        check(!feed.contains("private or expired"),
                "a feed detail does not add the story limit");
    }

    private static void resultMessages() {
        check("Added 1 item to the download queue.".equals(
                InContextAction.resultMessage(1, 0, 0)), "one added item is singular");
        check("Added 3 items to the download queue.".equals(
                InContextAction.resultMessage(3, 0, 0)), "several added items are plural");
        check("Already saved or queued.".equals(
                InContextAction.resultMessage(0, 2, 0)), "duplicates alone are reported");
        check("Nothing could be queued.".equals(
                InContextAction.resultMessage(0, 0, 1)), "a failure alone is reported");
        check("Nothing to download.".equals(
                InContextAction.resultMessage(0, 0, 0)), "nothing is reported plainly");
        String mixed = InContextAction.resultMessage(2, 1, 1);
        check(mixed.contains("Added 2 items"), "the added count is reported");
        check(mixed.contains("1 item was already saved or queued"),
                "the duplicate count is reported");
        check(mixed.contains("1 item could not be queued"),
                "the refused count is reported");
    }

    private static void ageLabels() {
        check("just now".equals(InContextAction.ageLabel(0L)), "zero is just now");
        check("just now".equals(InContextAction.ageLabel(-5L)),
                "a negative age is just now, never a future date");
        check("just now".equals(InContextAction.ageLabel(2999L)),
                "the just-now window is inclusive below 3s");
        check("3s ago".equals(InContextAction.ageLabel(3000L)), "seconds are shown");
        check("1 minute ago".equals(InContextAction.ageLabel(60000L)),
                "one minute is singular");
        check("2 minutes ago".equals(InContextAction.ageLabel(120000L)),
                "several minutes are plural");
    }

    private static ObservedMedia photo(String id, String url) {
        return ObservedMedia.single(id, DownloadKind.PHOTO, url, "image/jpeg",
                "alice", 1080, 1350, 0L, 0L, false);
    }

    private static ObservedMedia video(String id, String url, boolean audio) {
        return ObservedMedia.single(id, DownloadKind.VIDEO, url, "video/mp4",
                "alice", 1080, 1920, 0L, 0L, audio);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
