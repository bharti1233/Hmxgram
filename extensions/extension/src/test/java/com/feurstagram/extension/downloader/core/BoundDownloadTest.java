package com.feurstagram.extension.downloader.core;

/** Bound requests must fail closed without a selected identity or complete file. */
public final class BoundDownloadTest {
    private BoundDownloadTest() { }

    public static void main(String[] args) {
        BindingGeneration binding = new BindingGeneration();
        binding.invalidate(); // bind A
        long firstTap = binding.current();
        check(binding.matches(firstTap), "live A binding rejected");
        binding.invalidate(); // holder recycled to B
        check(!binding.matches(firstTap), "recycled holder accepted A confirmation");
        binding.invalidate(); // rapid transition back to A
        check(!binding.matches(firstTap), "A-B-A revived stale confirmation");
        long secondTap = binding.current();
        binding.invalidate(); // detached
        check(!binding.matches(secondTap), "detached control accepted confirmation");
        ObservedMedia selected = video("A", "https://cdn.example/A.mp4");
        ObservationSession observations = new ObservationSession();
        observations.observe(video("B", "https://cdn.example/B.mp4"), 1000L);
        ExplicitDownloadRequest request = request(selected, true);
        check(request.isReady() && request.targets().size() == 1
                && "A".equals(request.targets().get(0).id()), "background B replaced A");
        check(!request(selected, false).isReady(), "OFF accepted a request");
        check(!request(null, true).isReady(), "missing binding accepted");
        check(!request(video(null, "https://cdn.example/A.mp4"), true).isReady(),
                "URL substituted for missing identity");
        for (String suffix : new String[] {"cover.jpg", "cover.webp", "part.m4s",
                "part.ts", "playlist.m3u8", "manifest.mpd", "A.mp4?bytestart=0&byteend=100"}) {
            check(!request(video("A", "https://cdn.example/" + suffix), true).isReady(),
                    "accepted cover, manifest or segment: " + suffix);
        }
        DownloadQueue queue = new DownloadQueue();
        check(queue.tasks().isEmpty(), "observation queued work");
        check(queue.enqueue(request.targets().get(0).toMediaItem()).isAdded(), "enqueue failed");
        check(queue.enqueue(request.targets().get(0).toMediaItem()).status()
                == DownloadQueue.EnqueueStatus.DUPLICATE, "duplicate accepted");
        System.out.println("BoundDownloadTest: PASS");
    }

    private static ExplicitDownloadRequest request(ObservedMedia media, boolean enabled) {
        return ExplicitDownloadRequest.evaluateBound(media, InContextAction.Surface.REELS,
                enabled, 1000L, -1, false);
    }

    private static ObservedMedia video(String id, String url) {
        return ObservedMedia.single(id, DownloadKind.VIDEO, url, "video/mp4", null,
                1080, 1920, 0L, 0L, true);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
