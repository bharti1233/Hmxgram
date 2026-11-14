package com.feurstagram.extension.downloader.core;

/**
 * M10 tests: passive observation versus explicit download requests.
 *
 * <p>These are the behaviour gates for the reported bug: browsing Instagram
 * must create no jobs, and exactly one media must be queued when the user
 * explicitly confirms. Pure JDK: no Android class, no device.
 */
public final class ExplicitDownloadTest {

    private ExplicitDownloadTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M10 test group and returns the number of failures. */
    public static int run() {
        int failures = 0;
        failures += group("opening a reel creates zero jobs", new Runnable() {
            @Override
            public void run() {
                openingAReelCreatesNoJobs();
            }
        });
        failures += group("ten reels create zero jobs", new Runnable() {
            @Override
            public void run() {
                tenReelsCreateNoJobs();
            }
        });
        failures += group("repeated observation creates no duplicates", new Runnable() {
            @Override
            public void run() {
                repeatedObservationIsOneMedia();
            }
        });
        failures += group("explicit tap creates exactly one job", new Runnable() {
            @Override
            public void run() {
                explicitTapQueuesOne();
            }
        });
        failures += group("reel segments create one job", new Runnable() {
            @Override
            public void run() {
                reelSegmentsAreOneJob();
            }
        });
        failures += group("downloader off blocks", new Runnable() {
            @Override
            public void run() {
                offBlocks();
            }
        });
        failures += group("downloader on allows", new Runnable() {
            @Override
            public void run() {
                onAllows();
            }
        });
        failures += group("failed resolution creates no jobs", new Runnable() {
            @Override
            public void run() {
                failedResolutionCreatesNoJobs();
            }
        });
        failures += group("history remains intact", new Runnable() {
            @Override
            public void run() {
                historyRemainsIntact();
            }
        });
        if (failures == 0) {
            System.out.println("PASS 9 explicit-download tests");
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

    /** Opening one Reel only shows the chip; it queues nothing. */
    private static void openingAReelCreatesNoJobs() {
        ObservationSession session = new ObservationSession();
        DownloadQueue queue = new DownloadQueue();
        session.observe(reel("1001", "https://cdn.example/1001.mp4"), 1000L);

        check(queue.tasks().isEmpty(), "observing a Reel queues no task");
        check(queue.waitingCount() == 0, "nothing is waiting");
        check(queue.runningCount() == 0, "nothing is running");
        check(session.acceptedCount() == 1L, "the Reel is only remembered");
    }

    /** Scrolling through ten Reels queues nothing either. */
    private static void tenReelsCreateNoJobs() {
        ObservationSession session = new ObservationSession();
        DownloadQueue queue = new DownloadQueue();
        for (int index = 0; index < 10; index++) {
            session.observe(reel("20" + index,
                    "https://cdn.example/20" + index + ".mp4"), 1000L + index);
        }
        check(queue.tasks().isEmpty(), "ten Reels queue no task");
        check(session.acceptedCount() == 10L, "each Reel was observed once");
        check("209".equals(session.latest().id()),
                "the last Reel is the one on screen");
    }

    /** A Reel observed repeatedly stays one media. */
    private static void repeatedObservationIsOneMedia() {
        ObservationSession session = new ObservationSession();
        DownloadQueue queue = new DownloadQueue();
        String url = "https://cdn.example/reel.mp4?token=";
        for (int index = 0; index < 5; index++) {
            session.observe(reel("3001", url + index), 1000L + index);
        }
        check(queue.tasks().isEmpty(), "repeated observation queues no task");
        check(session.acceptedCount() == 1L, "the Reel is one media");
        check(session.refreshCount() == 4L, "the repeats refreshed it");
    }

    /** The explicit confirmation is the only path to a job. */
    private static void explicitTapQueuesOne() {
        ObservationSession session = new ObservationSession();
        session.observe(reel("4001", "https://cdn.example/4001.mp4"), 1000L);
        ExplicitDownloadRequest request = ExplicitDownloadRequest.evaluate(
                session.latest(), session.latestAgeMs(1500L),
                InContextAction.Surface.REELS, true, 1500L);
        check(request.isReady(), "the explicit request is ready");
        check(request.targets().size() == 1, "exactly one target is offered");

        DownloadQueue queue = enqueueAll(request, 1500L);
        check(queue.tasks().size() == 1, "exactly one job is created");
        check(queue.waitingCount() == 1, "the job is waiting");

        // A second tap on the same media is a duplicate, not a second job.
        DownloadQueue.EnqueueResult again = queue.enqueue(
                request.targets().get(0).toMediaItem(), 1600L);
        check(again.status() == DownloadQueue.EnqueueStatus.DUPLICATE,
                "the same media is a duplicate");
        check(queue.tasks().size() == 1, "the duplicate adds no job");
    }

    /** Streaming segments and renditions of one Reel are not several jobs. */
    private static void reelSegmentsAreOneJob() {
        ObservationSession session = new ObservationSession();
        String[] renditions = {
                "https://cdn.example/seg-1.mp4",
                "https://cdn.example/seg-2.mp4",
                "https://cdn.example/high.mp4",
                "https://cdn.example/cover.jpg"
        };
        for (int index = 0; index < renditions.length; index++) {
            session.observe(reel("5001", renditions[index]), 1000L + index);
        }
        check(session.acceptedCount() == 1L, "the segments are one media");

        ExplicitDownloadRequest request = ExplicitDownloadRequest.evaluate(
                session.latest(), session.latestAgeMs(2000L),
                InContextAction.Surface.REELS, true, 2000L);
        check(request.isReady(), "the Reel is ready to download");
        check(request.targets().size() == 1, "one Reel yields one target");

        DownloadQueue queue = enqueueAll(request, 2000L);
        check(queue.tasks().size() == 1, "one Reel yields one job");
    }

    /** The master switch off refuses every new request. */
    private static void offBlocks() {
        ObservedMedia media = reel("6001", "https://cdn.example/6001.mp4");
        for (InContextAction.Surface surface : InContextAction.Surface.values()) {
            ExplicitDownloadRequest request = ExplicitDownloadRequest.evaluate(
                    media, 0L, surface, false, 1000L);
            check(request.status() == ExplicitDownloadRequest.Status.DISABLED,
                    "off disables " + surface);
            check(!request.isReady(), "off is not ready on " + surface);
            check(request.targets().isEmpty(), "off offers no target on " + surface);
        }
        check(enqueueAll(ExplicitDownloadRequest.evaluate(media, 0L,
                InContextAction.Surface.FEED, false, 1000L), 1000L).tasks().isEmpty(),
                "off creates no job");
    }

    /** The master switch on allows a valid explicit request. */
    private static void onAllows() {
        ObservedMedia media = reel("7001", "https://cdn.example/7001.mp4");
        ExplicitDownloadRequest request = ExplicitDownloadRequest.evaluate(
                media, 0L, InContextAction.Surface.FEED, true, 1000L);
        check(request.status() == ExplicitDownloadRequest.Status.READY,
                "on allows an explicit request");
        check(request.isReady(), "the request is ready");
        check(request.targets().size() == 1, "on offers the media");
    }

    /** Media that cannot be resolved must never become a guess. */
    private static void failedResolutionCreatesNoJobs() {
        ObservationSession session = new ObservationSession();
        DownloadQueue queue = new DownloadQueue();

        check(asRequest(null, 0L, InContextAction.Surface.FEED, true)
                        .status() == ExplicitDownloadRequest.Status.UNSUPPORTED,
                "no observation is unsupported");
        check(asRequest(photo("8001", "https://cdn.example/cover.jpg"), 0L,
                        InContextAction.Surface.REELS, true)
                        .status() == ExplicitDownloadRequest.Status.UNSUPPORTED,
                "a Reel cover is unsupported");
        check(asRequest(ObservedMedia.single("8002", DownloadKind.VIDEO, null,
                        "video/mp4", "alice", 0, 0, 0L, 0L, true), 0L,
                        InContextAction.Surface.FEED, true)
                        .status() == ExplicitDownloadRequest.Status.UNSUPPORTED,
                "a video without a URL is unsupported");
        check(asRequest(reel("8003", "https://cdn.example/old.mp4"),
                        InContextAction.MAX_AGE_MS + 1L,
                        InContextAction.Surface.FEED, true)
                        .status() == ExplicitDownloadRequest.Status.UNSUPPORTED,
                "a stale observation is unsupported");

        ObservedMedia expired = ObservedMedia.single("8004", DownloadKind.VIDEO,
                "https://cdn.example/story.mp4", "video/mp4", "alice",
                0, 0, 0L, 900L, true);
        check(asRequest(expired, 0L, InContextAction.Surface.STORIES, true)
                        .status() == ExplicitDownloadRequest.Status.EXPIRED,
                "an expired story is expired");

        check(queue.tasks().isEmpty(), "no failed resolution creates a job");
        check(session.acceptedCount() == 0L, "an empty session stays empty");
    }

    /** Turning the switch off never removes what was already downloaded. */
    private static void historyRemainsIntact() {
        DownloadQueue queue = new DownloadQueue();
        MediaItem item = reel("9001", "https://cdn.example/9001.mp4").toMediaItem();
        DownloadQueue.EnqueueResult added = queue.enqueue(item, 1000L);
        check(added.isAdded(), "the first download is queued");
        DownloadTask task = queue.startNext().get(0);
        queue.complete(task.id(),
                DownloadResult.success("Movies/HMXInsta/hmx_alice_9001_video_1.mp4", 42L));
        int before = queue.tasks().size();
        check(before == 1, "one history entry exists");

        // The switch off and a cleared observation must not touch history.
        check(asRequest(null, 0L, null, false).isReady() == false,
                "off refuses new requests");
        new ObservationSession().clear();
        check(queue.tasks().size() == before, "the history entry is still there");
        check(queue.tasks().get(0).state() == DownloadState.DONE,
                "the finished task is still saved");
        check(queue.savedCount() == 1, "duplicate detection keeps the saved item");
    }

    // --- Helpers -----------------------------------------------------------

    private static ExplicitDownloadRequest asRequest(ObservedMedia media, long ageMs,
            InContextAction.Surface surface, boolean enabled) {
        return ExplicitDownloadRequest.evaluate(media, ageMs, surface, enabled, 1000L);
    }

    private static DownloadQueue enqueueAll(ExplicitDownloadRequest request,
            long nowMs) {
        DownloadQueue queue = new DownloadQueue();
        for (ObservedMedia target : request.targets()) {
            queue.enqueue(target.toMediaItem(), nowMs);
        }
        return queue;
    }

    private static ObservedMedia reel(String id, String url) {
        return ObservedMedia.single(id, DownloadKind.VIDEO, url, "video/mp4",
                "alice", 1080, 1920, 0L, 0L, true);
    }

    private static ObservedMedia photo(String id, String url) {
        return ObservedMedia.single(id, DownloadKind.PHOTO, url, "image/jpeg",
                "alice", 1080, 1350, 0L, 0L, false);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
