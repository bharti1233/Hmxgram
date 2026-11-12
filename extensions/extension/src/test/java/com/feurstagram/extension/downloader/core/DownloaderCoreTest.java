package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Plain-JDK unit tests for the pure downloader core.
 *
 * <p>There is deliberately no JUnit dependency: the core is tested with
 * {@code javac --release 11} and a normal {@code main} entry point, exactly as
 * CI runs it. A non-zero exit code fails the build.
 */
public final class DownloaderCoreTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        run("kind mapping", DownloaderCoreTest::kindMapping);
        run("error taxonomy", DownloaderCoreTest::errorTaxonomy);
        run("result validation", DownloaderCoreTest::resultValidation);
        run("media item validation", DownloaderCoreTest::mediaItemValidation);
        run("expiry rules", DownloaderCoreTest::expiryRules);
        run("naming sanitisation", DownloaderCoreTest::namingSanitisation);
        run("mime and extension mapping", DownloaderCoreTest::mimeAndExtensionMapping);
        run("file naming", DownloaderCoreTest::fileNaming);
        run("duplicate keys", DownloaderCoreTest::duplicateKeys);
        run("duplicate detector", DownloaderCoreTest::duplicateDetector);
        run("task state machine", DownloaderCoreTest::taskStateMachine);
        run("queue capacity", DownloaderCoreTest::queueCapacity);
        run("queue concurrency", DownloaderCoreTest::queueConcurrency);
        run("queue duplicate handling", DownloaderCoreTest::queueDuplicates);
        run("queue retry policy", DownloaderCoreTest::queueRetry);
        run("queue clear and retention", DownloaderCoreTest::queueClearAndRetention);
        run("queue expiry refusal", DownloaderCoreTest::queueExpiryRefusal);
        run("media source expansion", DownloaderCoreTest::mediaSourceExpansion);
        run("repository history", DownloaderCoreTest::repositoryHistory);
        run("storage plans", DownloaderCoreTest::storagePlans);
        run("mime round trip", DownloaderCoreTest::mimeRoundTrip);

        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("FAILED " + failures.size() + " of "
                    + (passed + failures.size()) + " tests");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println();
        System.out.println("PASS " + passed + " downloader-core tests");
    }

    private static void kindMapping() {
        eq(DownloadKind.PHOTO, DownloadKind.fromInstagramMediaType(1), "type 1 is photo");
        eq(DownloadKind.VIDEO, DownloadKind.fromInstagramMediaType(2), "type 2 is video");
        eq(DownloadKind.CAROUSEL, DownloadKind.fromInstagramMediaType(8), "type 8 is carousel");
        check(DownloadKind.CAROUSEL.isContainer(), "carousel is a container");
        check(!DownloadKind.PHOTO.isContainer(), "photo is not a container");
        check(DownloadKind.PHOTO.hasInstagramMediaType(), "photo maps to a media type");
        check(!DownloadKind.AUDIO.hasInstagramMediaType(), "audio is derived");
        eq(8, DownloadKind.CAROUSEL.instagramMediaType(), "carousel media type");
        expect(IllegalArgumentException.class,
                () -> DownloadKind.fromInstagramMediaType(7),
                "unknown media type is rejected");
    }

    private static void errorTaxonomy() {
        check(DownloadError.NETWORK.isRecoverable(), "network is recoverable");
        check(DownloadError.TIMEOUT.isRecoverable(), "timeout is recoverable");
        check(DownloadError.SERVER.isRecoverable(), "server is recoverable");
        check(!DownloadError.EXPIRED_LINK.isRecoverable(), "expired link is final");
        check(!DownloadError.EXPIRED_MEDIA.isRecoverable(), "expired story is final");
        check(!DownloadError.STORAGE.isRecoverable(), "storage is final");
        check(!DownloadError.INVALID_MEDIA.isRecoverable(), "invalid media is final");
        check(!DownloadError.UNSUPPORTED.isRecoverable(), "unsupported is final");
        check(!DownloadError.CANCELLED.isRecoverable(), "cancelled is final");
        check(!DownloadError.UNKNOWN.isRecoverable(), "unknown is final");

        List<String> codes = new ArrayList<String>();
        for (DownloadError error : DownloadError.values()) {
            check(error.code() != null && !error.code().isEmpty(), "code is present");
            check(!codes.contains(error.code()), "code is unique: " + error.code());
            codes.add(error.code());
            eq(error, DownloadError.fromCode(error.code()), "code round trip");
            check(!error.message().isEmpty(), "message is present");
        }
        expect(IllegalArgumentException.class,
                () -> DownloadError.fromCode("nope"),
                "unknown code is rejected");
        expect(IllegalArgumentException.class,
                () -> DownloadError.fromCode(null),
                "null code is rejected");
    }

    private static void resultValidation() {
        DownloadResult success = DownloadResult.success("/gallery/a.jpg", 1234L);
        check(success.isSuccess(), "success is success");
        eq(null, success.error(), "success has no error");
        eq("/gallery/a.jpg", success.path(), "success path");
        eq(1234L, success.bytesWritten(), "success bytes");

        DownloadResult failure = DownloadResult.failure(DownloadError.NETWORK);
        check(!failure.isSuccess(), "failure is not success");
        eq(DownloadError.NETWORK, failure.error(), "failure error");
        eq(null, failure.path(), "failure has no path");
        eq(0L, failure.bytesWritten(), "failure wrote nothing");

        DownloadResult cancelled = DownloadResult.cancelled();
        eq(DownloadError.CANCELLED, cancelled.error(), "cancelled error");

        expect(IllegalArgumentException.class,
                () -> DownloadResult.success("", 1L), "empty path rejected");
        expect(IllegalArgumentException.class,
                () -> DownloadResult.success("a", -1L), "negative bytes rejected");
        expect(IllegalArgumentException.class,
                () -> DownloadResult.failure(null), "null error rejected");
        expect(IllegalArgumentException.class,
                () -> DownloadResult.failure(DownloadError.CANCELLED),
                "cancelled needs cancelled()");
        eq(success, DownloadResult.success("/gallery/a.jpg", 1234L), "value equality");
    }

    private static void mediaItemValidation() {
        MediaItem photo = photo("m1", "https://cdn.example/a.jpg", "alice");
        eq("m1", photo.id(), "id");
        eq(DownloadKind.PHOTO, photo.kind(), "kind");
        eq("https://cdn.example/a.jpg", photo.url(), "url");
        eq("alice", photo.owner(), "owner");
        eq(-1L, photo.sizeBytes(), "unknown size");
        check(photo.children().isEmpty(), "photo has no children");
        check(!photo.hasExpiry(), "photo does not expire");

        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO).build(),
                "photo needs a URL");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("file:///etc/passwd").build(),
                "non-http URL rejected");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("javascript:alert(1)").build(),
                "javascript URL rejected");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.CAROUSEL).build(),
                "carousel needs a child");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.CAROUSEL)
                        .url("https://cdn.example/a.jpg")
                        .addChild(photo).build(),
                "carousel has no URL");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("https://cdn.example/a.jpg")
                        .addChild(photo).build(),
                "only a carousel may have children");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("https://cdn.example/a.jpg")
                        .sizeBytes(-2L).build(),
                "size below -1 rejected");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("https://cdn.example/a.jpg")
                        .size(-1, 0).build(),
                "negative dimension rejected");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("https://cdn.example/a.jpg")
                        .expiresAtEpochMs(-1L).build(),
                "negative expiry rejected");

        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .id("c1")
                .addChild(photo)
                .addChild(video("m2", "https://cdn.example/b.mp4", "alice"))
                .build();
        check(carousel.kind().isContainer(), "carousel is a container");
        eq(2, carousel.children().size(), "carousel children");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.CAROUSEL)
                        .addChild(carousel),
                "nested carousel rejected");
        expect(UnsupportedOperationException.class,
                () -> carousel.children().add(photo),
                "children are immutable");

        MediaItem audio = new MediaItem.Builder(DownloadKind.AUDIO)
                .url("https://cdn.example/a.m4a")
                .build();
        MediaItem cover = new MediaItem.Builder(DownloadKind.COVER)
                .url("https://cdn.example/cover.jpg")
                .build();
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.CAROUSEL).addChild(audio),
                "audio cannot be a carousel child");
        expect(IllegalArgumentException.class,
                () -> new MediaItem.Builder(DownloadKind.CAROUSEL).addChild(cover),
                "cover cannot be a carousel child");
    }

    private static void expiryRules() {
        MediaItem story = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("s1")
                .url("https://cdn.example/story.jpg")
                .expiresAtEpochMs(1000L)
                .build();
        check(story.hasExpiry(), "story has an expiry");
        check(!story.isExpired(999L), "story is live before expiry");
        check(story.isExpired(1000L), "story expires exactly at the boundary");
        eq(null, story.downloadability(999L), "live story is downloadable");
        eq(DownloadError.EXPIRED_MEDIA, story.downloadability(1000L),
                "expired story is refused");

        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .id("c-expired")
                .addChild(story)
                .build();
        eq(DownloadError.EXPIRED_MEDIA, carousel.downloadability(2000L),
                "carousel with an expired child is refused");

        MediaItem expiringCarousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .id("c-story")
                .expiresAtEpochMs(1500L)
                .addChild(photo("c-live", "https://cdn.example/live.jpg", "x"))
                .build();
        check(!expiringCarousel.isExpired(1499L), "carousel is live before expiry");
        eq(null, expiringCarousel.downloadability(1499L),
                "live carousel is downloadable");
        eq(DownloadError.EXPIRED_MEDIA, expiringCarousel.downloadability(1500L),
                "carousel-level expiry is honoured");
        MediaItem expanded = MediaSource.flatten(
                Arrays.asList(expiringCarousel)).get(0);
        eq(1500L, expanded.expiresAtEpochMs(),
                "carousel expiry is applied to its child");
        eq(DownloadError.EXPIRED_MEDIA, expanded.downloadability(1500L),
                "expanded child inherits the carousel expiry");

        MediaItem earlierChild = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("c-earlier")
                .url("https://cdn.example/earlier.jpg")
                .expiresAtEpochMs(1200L)
                .build();
        MediaItem laterCarousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .id("c-later")
                .expiresAtEpochMs(1500L)
                .addChild(earlierChild)
                .build();
        eq(1200L, MediaSource.flatten(Arrays.asList(laterCarousel))
                        .get(0).expiresAtEpochMs(),
                "the earlier child expiry wins");
    }

    private static void namingSanitisation() {
        eq("etc_passwd", MediaNaming.sanitizeSegment("../../etc/passwd"),
                "path traversal collapses to one segment");
        eq("media", MediaNaming.sanitizeSegment(".."), "dot-dot falls back");
        eq("media", MediaNaming.sanitizeSegment("CON"), "reserved name falls back");
        eq("media", MediaNaming.sanitizeSegment(null), "null falls back");
        eq("a_b", MediaNaming.sanitizeSegment("a///b"), "separators collapse");
        eq("line_one", MediaNaming.sanitizeSegment("line\none"), "control chars replaced");
        String longName = MediaNaming.sanitizeSegment(repeat("x", 200));
        eq(48, longName.length(), "segment is capped");
        check(!MediaNaming.sanitizeSegment("a/b").contains("/"), "no slash survives");
        check(!MediaNaming.sanitizeSegment("..\\..\\x").contains("\\"), "no backslash survives");
    }

    private static void mimeAndExtensionMapping() {
        eq("jpg", MediaNaming.extensionForMime("image/jpeg"), "jpeg");
        eq("jpg", MediaNaming.extensionForMime("IMAGE/JPEG; charset=binary"),
                "mime is normalised and parameters stripped");
        eq("png", MediaNaming.extensionForMime("image/png"), "png");
        eq("webp", MediaNaming.extensionForMime("image/webp"), "webp");
        eq("mp4", MediaNaming.extensionForMime("video/mp4"), "mp4");
        eq("m4a", MediaNaming.extensionForMime("audio/mp4"), "m4a");
        eq("mp3", MediaNaming.extensionForMime("audio/mpeg"), "mp3");
        eq("bin", MediaNaming.extensionForMime("application/x-unknown"), "unknown mime");
        eq("bin", MediaNaming.extensionForMime(null), "null mime");

        eq("image/jpeg", MediaNaming.mimeForExtension("JPG"), "extension is case-insensitive");
        eq("video/mp4", MediaNaming.mimeForExtension(".mp4"), "leading dot accepted");
        eq(null, MediaNaming.mimeForExtension("nope"), "unknown extension");

        MediaItem barePhoto = new MediaItem.Builder(DownloadKind.PHOTO)
                .url("https://cdn.example/a").build();
        MediaItem bareVideo = new MediaItem.Builder(DownloadKind.VIDEO)
                .url("https://cdn.example/a").build();
        MediaItem bareAudio = new MediaItem.Builder(DownloadKind.AUDIO)
                .url("https://cdn.example/a").build();
        eq("jpg", MediaNaming.extensionFor(barePhoto), "photo defaults to jpg");
        eq("mp4", MediaNaming.extensionFor(bareVideo), "video defaults to mp4");
        eq("m4a", MediaNaming.extensionFor(bareAudio), "audio defaults to m4a");
        eq("image/jpeg", MediaNaming.mimeFor(barePhoto), "photo mime");
        eq("video/mp4", MediaNaming.mimeFor(bareVideo), "video mime");
        eq("audio/mp4", MediaNaming.mimeFor(bareAudio), "audio mime");

        MediaItem noisyMime = new MediaItem.Builder(DownloadKind.PHOTO)
                .url("https://cdn.example/noisy.jpg")
                .mimeType(" IMAGE/JPEG; charset=binary ")
                .build();
        eq("image/jpeg", MediaNaming.mimeFor(noisyMime),
                "declared mime is normalised for storage");
    }

    private static void fileNaming() {
        MediaItem item = photo("abc123", "https://cdn.example/a.jpg?token=1", "alice");
        eq("hmx_alice_abc123_photo_1700000000000.jpg",
                MediaNaming.buildFileName(item, 1700000000000L),
                "deterministic file name");

        MediaItem noId = new MediaItem.Builder(DownloadKind.VIDEO)
                .url("https://cdn.example/a.mp4")
                .owner("bob")
                .build();
        String fallback = MediaNaming.buildFileName(noId, 7L);
        check(fallback.startsWith("hmx_bob_"), "fallback keeps owner: " + fallback);
        check(fallback.endsWith("_video_7.mp4"), "fallback keeps kind and time: " + fallback);
        check(!fallback.contains("/") && !fallback.contains(".."), "fallback is safe");

        MediaItem longOwner = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("id1")
                .url("https://cdn.example/a.jpg")
                .owner(repeat("o", 200))
                .build();
        String longName = MediaNaming.buildFileName(longOwner, 123L);
        check(longName.length() <= MediaNaming.MAX_FILE_NAME_LENGTH,
                "file name is capped: " + longName.length());
        check(longName.endsWith(".jpg"), "extension survives the cap");

        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .addChild(item).build();
        expect(IllegalArgumentException.class,
                () -> MediaNaming.buildFileName(carousel, 1L),
                "carousel has no file name");
        expect(IllegalArgumentException.class,
                () -> MediaNaming.buildFileName(item, -1L),
                "negative timestamp rejected");

        MediaItem slashId = photo("a/b", "https://cdn.example/one.jpg", "alice");
        MediaItem queryId = photo("a?b", "https://cdn.example/two.jpg", "alice");
        String slashName = MediaNaming.buildFileName(slashId, 7L);
        String queryName = MediaNaming.buildFileName(queryId, 7L);
        check(!slashName.equals(queryName), "sanitised ids do not collide");
        check(slashName.startsWith("hmx_alice_a_b_"),
                "sanitised id stays readable: " + slashName);
        check(!slashName.contains("/") && !slashName.contains("?"),
                "sanitised id is a safe segment");
    }

    private static void duplicateKeys() {
        MediaItem withId = photo("42", "https://cdn.example/a.jpg?x=1", "alice");
        eq("id:42", MediaNaming.duplicateKey(withId), "id wins over URL");

        MediaItem withoutId = new MediaItem.Builder(DownloadKind.PHOTO)
                .url("https://cdn.example/a.jpg?token=secret#frag").build();
        eq("url:https://cdn.example/a.jpg", MediaNaming.duplicateKey(withoutId),
                "query and fragment stripped");
        eq("", MediaNaming.stripQuery(null), "null URL");
        eq("https://cdn.example/a.jpg", MediaNaming.stripQuery(
                "https://cdn.example/a.jpg#x"), "fragment stripped");
        eq("https://cdn.example/a.jpg", MediaNaming.stripQuery(
                "https://cdn.example/a.jpg?a=1#b"), "query before fragment");

        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .addChild(photo("a", "https://cdn.example/a.jpg", "x"))
                .addChild(photo("b", "https://cdn.example/b.jpg", "x"))
                .build();
        eq("carousel:id:a|id:b", MediaNaming.duplicateKey(carousel),
                "carousel combines child keys");

        MediaItem carouselNoIds = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .addChild(new MediaItem.Builder(DownloadKind.PHOTO)
                        .url("https://cdn.example/a.jpg?x=1").build())
                .addChild(new MediaItem.Builder(DownloadKind.VIDEO)
                        .url("https://cdn.example/b.mp4?y=2").build())
                .build();
        eq("carousel:url:https://cdn.example/a.jpg|url:https://cdn.example/b.mp4",
                MediaNaming.duplicateKey(carouselNoIds),
                "carousel combines URL keys");
    }

    private static void duplicateDetector() {
        DuplicateDetector detector = new DuplicateDetector(2);
        check(detector.remember("a"), "first key is new");
        check(!detector.remember("a"), "second key is not new");
        check(detector.isSeen("a"), "key is seen");
        check(!detector.isSeen("b"), "other key is not seen");
        check(detector.remember("b"), "second key added");
        check(detector.remember("c"), "third key evicts the oldest");
        check(!detector.isSeen("a"), "oldest key evicted");
        eq(2, detector.size(), "capacity is respected");
        eq(2, detector.capacity(), "capacity reported");
        detector.clear();
        eq(0, detector.size(), "clear empties the set");

        expect(IllegalArgumentException.class,
                () -> detector.remember(" "), "blank key rejected");
        expect(IllegalArgumentException.class,
                () -> new DuplicateDetector(0), "zero capacity rejected");

        DuplicateDetector itemDetector = new DuplicateDetector();
        MediaItem item = photo("m1", "https://cdn.example/a.jpg", "alice");
        check(itemDetector.remember(item), "item remembered");
        check(itemDetector.isSeen(item), "item seen");
        check(!itemDetector.remember(item), "item not remembered twice");
    }

    private static void taskStateMachine() {
        DownloadTask task = new DownloadTask("t1", photo("m1", "https://cdn.example/a.jpg", "x"));
        eq(DownloadState.QUEUED, task.state(), "starts queued");
        eq(0, task.attempts(), "no attempt yet");
        check(!task.isTerminal(), "queued is active");

        task.start();
        eq(DownloadState.RUNNING, task.state(), "starts running");
        eq(1, task.attempts(), "attempt counted");
        task.succeed(DownloadResult.success("/gallery/a.jpg", 10L));
        eq(DownloadState.DONE, task.state(), "done");
        check(task.isTerminal(), "done is terminal");
        eq("/gallery/a.jpg", task.result().path(), "result retained");

        expect(IllegalStateException.class, task::start, "cannot restart done");
        expect(IllegalStateException.class,
                () -> task.succeed(DownloadResult.success("/x", 1L)),
                "cannot complete done");

        DownloadTask retrying = new DownloadTask("t2",
                photo("m2", "https://cdn.example/b.jpg", "x"), 2, 5L);
        retrying.start();
        retrying.fail(DownloadError.NETWORK);
        eq(DownloadState.FAILED, retrying.state(), "failed");
        check(retrying.canRetry(), "recoverable failure can retry");
        check(retrying.retry(), "retry requeues");
        eq(DownloadState.QUEUED, retrying.state(), "requeued");
        retrying.start();
        retrying.fail(DownloadError.SERVER);
        check(!retrying.canRetry(), "attempts exhausted");
        check(!retrying.retry(), "retry refused when exhausted");

        DownloadTask storageFailure = new DownloadTask("t3",
                photo("m3", "https://cdn.example/c.jpg", "x"));
        storageFailure.start();
        storageFailure.fail(DownloadError.STORAGE);
        check(!storageFailure.canRetry(), "storage failure is final");
        check(!storageFailure.retry(), "final failure is not retried");

        DownloadTask cancelled = new DownloadTask("t4",
                photo("m4", "https://cdn.example/d.jpg", "x"));
        cancelled.cancel();
        eq(DownloadState.CANCELLED, cancelled.state(), "cancelled while queued");
        eq(DownloadError.CANCELLED, cancelled.error(), "cancel error");
        expect(IllegalStateException.class, cancelled::cancel, "cannot cancel twice");

        DownloadTask cancelledInFlight = new DownloadTask("t5",
                photo("m5", "https://cdn.example/e.jpg", "x"));
        cancelledInFlight.start();
        cancelledInFlight.fail(DownloadError.CANCELLED);
        eq(DownloadState.CANCELLED, cancelledInFlight.state(), "cancel error maps to state");

        expect(IllegalStateException.class,
                () -> new DownloadTask("t6",
                        photo("m6", "https://cdn.example/f.jpg", "x")).succeed(
                                DownloadResult.success("/x", 1L)),
                "cannot complete before start");
        expect(IllegalArgumentException.class,
                () -> new DownloadTask(" ", photo("m7", "https://cdn.example/g.jpg", "x")),
                "blank id rejected");
    }

    private static void queueCapacity() {
        DownloadQueue queue = new DownloadQueue(2, 2, 3);
        eq(DownloadQueue.EnqueueStatus.ADDED, queue.enqueue(item("a")).status(), "first added");
        eq(DownloadQueue.EnqueueStatus.ADDED, queue.enqueue(item("b")).status(), "second added");
        eq(DownloadQueue.EnqueueStatus.FULL, queue.enqueue(item("c")).status(), "third is full");
        eq(2, queue.waitingCount(), "waiting limit is 2");

        eq(2, queue.startNext().size(), "two started");
        eq(0, queue.waitingCount(), "waiting drained");
        eq(2, queue.runningCount(), "two running");
        eq(DownloadQueue.EnqueueStatus.ADDED, queue.enqueue(item("d")).status(),
                "waiting slot freed by starting");

        DownloadQueue defaults = new DownloadQueue();
        eq(20, defaults.maxWaiting(), "default waiting");
        eq(2, defaults.maxConcurrent(), "default concurrency");
        eq(3, defaults.maxAttempts(), "default attempts");

        expect(IllegalArgumentException.class,
                () -> new DownloadQueue(0, 1, 1), "zero waiting rejected");
        expect(IllegalArgumentException.class,
                () -> new DownloadQueue(1, 0, 1), "zero concurrency rejected");
        expect(IllegalArgumentException.class,
                () -> new DownloadQueue(1, 1, 0), "zero attempts rejected");
    }

    private static void queueConcurrency() {
        DownloadQueue queue = new DownloadQueue(5, 2, 3);
        queue.enqueue(item("a"));
        queue.enqueue(item("b"));
        queue.enqueue(item("c"));

        List<DownloadTask> firstWave = queue.startNext();
        eq(2, firstWave.size(), "concurrency limit enforced");
        eq(DownloadState.RUNNING, firstWave.get(0).state(), "first running");
        eq(2, queue.runningCount(), "two running");
        eq(0, queue.startNext().size(), "no slot available");

        queue.complete(firstWave.get(0).id(),
                DownloadResult.success("/gallery/a.jpg", 1L));
        eq(1, queue.runningCount(), "one running after completion");
        List<DownloadTask> secondWave = queue.startNext();
        eq(1, secondWave.size(), "freed slot fills");
        eq("task-3", secondWave.get(0).id(), "next waiting task starts");
        eq(2, queue.runningCount(), "limit still respected");
        eq(0, queue.waitingCount(), "all started");
    }

    private static void queueDuplicates() {
        DownloadQueue queue = new DownloadQueue(5, 1, 3);
        MediaItem item = item("dup");
        DownloadQueue.EnqueueResult first = queue.enqueue(item);
        eq(DownloadQueue.EnqueueStatus.ADDED, first.status(), "first added");
        eq(DownloadQueue.EnqueueStatus.DUPLICATE, queue.enqueue(item).status(),
                "duplicate while queued");

        queue.startNext();
        eq(DownloadQueue.EnqueueStatus.DUPLICATE, queue.enqueue(item).status(),
                "duplicate while running");

        queue.complete(first.task().id(), DownloadResult.success("/gallery/dup.jpg", 1L));
        eq(1, queue.savedCount(), "successful item remembered");
        eq(DownloadQueue.EnqueueStatus.DUPLICATE, queue.enqueue(item).status(),
                "duplicate after success");
        check(queue.isDuplicate(item), "isDuplicate after success");

        MediaItem different = item("other");
        eq(DownloadQueue.EnqueueStatus.ADDED, queue.enqueue(different).status(),
                "different id is not a duplicate");

        MediaItem noIdA = new MediaItem.Builder(DownloadKind.PHOTO)
                .url("https://cdn.example/same.jpg?token=a").build();
        MediaItem noIdB = new MediaItem.Builder(DownloadKind.PHOTO)
                .url("https://cdn.example/same.jpg?token=b").build();
        DownloadQueue urlQueue = new DownloadQueue(5, 1, 3);
        eq(DownloadQueue.EnqueueStatus.ADDED, urlQueue.enqueue(noIdA).status(),
                "url key added");
        eq(DownloadQueue.EnqueueStatus.DUPLICATE, urlQueue.enqueue(noIdB).status(),
                "same URL without query is duplicate");

        DownloadQueue cancelQueue = new DownloadQueue(5, 1, 3);
        DownloadQueue.EnqueueResult cancelled = cancelQueue.enqueue(item("cancel-me"));
        check(cancelQueue.cancel(cancelled.task().id()), "cancel queued task");
        eq(DownloadQueue.EnqueueStatus.ADDED,
                cancelQueue.enqueue(item("cancel-me")).status(),
                "cancelled item can be queued again");

        DownloadQueue failQueue = new DownloadQueue(5, 1, 3);
        DownloadQueue.EnqueueResult failed = failQueue.enqueue(item("fail-me"));
        failQueue.startNext();
        check(!failQueue.fail(failed.task().id(), DownloadError.STORAGE),
                "storage failure is not requeued");
        eq(DownloadQueue.EnqueueStatus.ADDED,
                failQueue.enqueue(item("fail-me")).status(),
                "permanently failed item can be queued again");

        InMemoryDownloadRepository persisted = new InMemoryDownloadRepository(5);
        persisted.save(doneTask("persisted-done", "persisted-done"));
        persisted.save(failedTask("persisted-failed", "persisted-failed"));
        DownloadQueue restored = new DownloadQueue(5, 1, 3);
        eq(1, restored.seedSaved(persisted),
                "only completed history seeds the duplicate set");
        eq(DownloadQueue.EnqueueStatus.DUPLICATE,
                restored.enqueue(item("persisted-done")).status(),
                "persisted completed item is a duplicate after restart");
        eq(DownloadQueue.EnqueueStatus.ADDED,
                restored.enqueue(item("persisted-failed")).status(),
                "persisted failure is not a duplicate");
        check(restored.rememberSaved("id:manual-seed"), "manual key seeded");
        check(!restored.rememberSaved("id:manual-seed"), "seeding is idempotent");
        eq(DownloadQueue.EnqueueStatus.DUPLICATE,
                restored.enqueue(item("manual-seed")).status(),
                "manually seeded key is a duplicate");
        expect(IllegalArgumentException.class,
                () -> restored.rememberSaved((String) null),
                "null seed key rejected");
        expect(IllegalArgumentException.class,
                () -> restored.rememberSaved((MediaItem) null),
                "null seed item rejected");
        expect(IllegalArgumentException.class,
                () -> restored.seedSaved(null),
                "null seed repository rejected");
    }

    private static void queueRetry() {
        DownloadQueue queue = new DownloadQueue(2, 1, 3);
        DownloadTask task = queue.enqueue(item("retry")).task();
        queue.startNext();
        check(queue.fail(task.id(), DownloadError.NETWORK), "network failure requeued");
        eq(DownloadState.QUEUED, task.state(), "task is queued again");
        eq(1, queue.waitingCount(), "one waiting after requeue");

        queue.startNext();
        check(queue.fail(task.id(), DownloadError.SERVER), "server failure requeued");
        queue.startNext();
        check(!queue.fail(task.id(), DownloadError.TIMEOUT), "attempts exhausted");
        eq(DownloadState.FAILED, task.state(), "task stays failed");
        check(!queue.retry(task.id()), "manual retry refused when exhausted");

        DownloadQueue finalQueue = new DownloadQueue(2, 1, 3);
        DownloadTask finalTask = finalQueue.enqueue(item("final")).task();
        finalQueue.startNext();
        check(!finalQueue.fail(finalTask.id(), DownloadError.STORAGE),
                "storage failure is final");
        check(!finalQueue.retry(finalTask.id()), "final failure cannot be retried");

        DownloadQueue fullQueue = new DownloadQueue(1, 1, 3);
        DownloadTask blocked = fullQueue.enqueue(item("blocked")).task();
        fullQueue.startNext();
        DownloadTask blocker = fullQueue.enqueue(item("blocker")).task();
        check(!fullQueue.fail(blocked.id(), DownloadError.NETWORK),
                "requeue refused when waiting list is full");
        check(blocked.canRetry(), "task still retryable");
        check(!fullQueue.retry(blocked.id()), "manual retry refused while full");
        check(fullQueue.cancel(blocker.id()), "blocker cancelled");
        check(fullQueue.retry(blocked.id()), "manual retry succeeds when room appears");
        eq(DownloadState.QUEUED, blocked.state(), "manual retry queued");

        DownloadQueue duplicateGuard = new DownloadQueue(1, 1, 3);
        DownloadTask stale = duplicateGuard.enqueue(item("manual-dup")).task();
        duplicateGuard.startNext();
        DownloadTask blocker2 = duplicateGuard.enqueue(item("blocker-2")).task();
        check(!duplicateGuard.fail(stale.id(), DownloadError.NETWORK),
                "no room to requeue automatically");
        check(duplicateGuard.cancel(blocker2.id()), "second blocker cancelled");
        DownloadQueue.EnqueueResult fresh = duplicateGuard.enqueue(item("manual-dup"));
        eq(DownloadQueue.EnqueueStatus.ADDED, fresh.status(),
                "failed item can be enqueued as a new task");
        check(!duplicateGuard.retry(stale.id()),
                "manual retry refuses an active duplicate");
        duplicateGuard.startNext();
        duplicateGuard.complete(fresh.task().id(),
                DownloadResult.success("/gallery/manual.jpg", 1L));
        check(!duplicateGuard.retry(stale.id()),
                "manual retry refuses a saved duplicate");
    }

    private static void queueClearAndRetention() {
        DownloadQueue queue = new DownloadQueue(2, 1, 3);
        DownloadTask finished = queue.enqueue(item("finished")).task();
        queue.startNext();
        queue.complete(finished.id(), DownloadResult.success("/gallery/finished.jpg", 1L));
        DownloadTask running = queue.enqueue(item("running")).task();
        queue.startNext();
        eq(1, queue.runningCount(), "running task before clear");

        queue.clear();
        eq(1, queue.runningCount(), "clear keeps running tasks");
        check(queue.task(finished.id()) == null, "clear forgets the finished task");
        check(queue.task(running.id()) != null, "clear keeps the running task");
        eq(0, queue.savedCount(), "clear forgets the duplicate history");
        queue.complete(running.id(), DownloadResult.success("/gallery/running.jpg", 1L));
        eq(1, queue.savedCount(), "a task completing after clear is remembered");
        eq("task-3", queue.enqueue(item("next")).task().id(),
                "clear does not reuse task ids");

        DownloadQueue bounded = new DownloadQueue(1, 1, 1);
        int total = DownloadQueue.MAX_FINISHED_TASKS + 1;
        for (int i = 0; i < total; i++) {
            DownloadTask task = bounded.enqueue(item("finish-" + i)).task();
            bounded.startNext();
            bounded.complete(task.id(),
                    DownloadResult.success("/gallery/" + i + ".jpg", 1L));
        }
        eq(DownloadQueue.MAX_FINISHED_TASKS, bounded.tasks().size(),
                "finished task map is bounded");
        check(bounded.task("task-1") == null, "oldest finished task is pruned");
        check(bounded.task("task-" + total) != null, "newest finished task is retained");
    }

    private static void queueExpiryRefusal() {
        MediaItem story = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("story")
                .url("https://cdn.example/story.jpg")
                .expiresAtEpochMs(1000L)
                .build();
        DownloadQueue queue = new DownloadQueue();
        expect(IllegalArgumentException.class,
                () -> queue.enqueue(story, 1000L), "expired story refused");
        eq(0, queue.waitingCount(), "refused story did not occupy a slot");
        eq(DownloadQueue.EnqueueStatus.ADDED, queue.enqueue(story, 999L).status(),
                "live story accepted");

        expect(IllegalArgumentException.class,
                () -> queue.enqueue((MediaItem) null, 999L), "null item refused");

        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .addChild(item("child"))
                .build();
        expect(IllegalArgumentException.class,
                () -> queue.enqueue(carousel, 999L),
                "carousel must be expanded before enqueue");
    }

    private static void mediaSourceExpansion() {
        MediaItem a = photo("a", "https://cdn.example/a.jpg", "x");
        MediaItem b = photo("b", "https://cdn.example/b.jpg", "x");
        MediaItem c = video("c", "https://cdn.example/c.mp4", "x");
        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .id("carousel")
                .addChild(a)
                .addChild(b)
                .build();
        MediaItem reel = video("d", "https://cdn.example/d.mp4", "x");

        List<MediaItem> flat = MediaSource.flatten(Arrays.asList(carousel, reel));
        eq(3, flat.size(), "carousel expanded");
        eq(a, flat.get(0), "first child first");
        eq(b, flat.get(1), "second child second");
        eq(reel, flat.get(2), "following item preserved");

        MediaSource source = MediaSource.of(Arrays.asList(carousel, reel));
        check(source.hasItems(), "source has items");
        eq(3, source.downloadableItems().size(), "source expands carousels");

        MediaSource empty = MediaSource.of(new ArrayList<MediaItem>());
        check(!empty.hasItems(), "empty source");
        expect(IllegalArgumentException.class,
                () -> MediaSource.flatten(null), "null list rejected");
        List<MediaItem> withNull = new ArrayList<MediaItem>();
        withNull.add(null);
        expect(IllegalArgumentException.class,
                () -> MediaSource.flatten(withNull), "null item rejected");
    }

    private static void repositoryHistory() {
        InMemoryDownloadRepository repository = new InMemoryDownloadRepository(3);
        DownloadTask active = new DownloadTask("active", item("active"));
        expect(IllegalArgumentException.class,
                () -> repository.save(active), "active task cannot be stored");

        DownloadTask done = doneTask("done", "done");
        DownloadTask failed = failedTask("failed", "failed");
        DownloadTask cancelled = new DownloadTask("cancelled", item("cancelled"));
        cancelled.cancel();

        repository.save(done);
        repository.save(failed);
        repository.save(cancelled);
        eq(3, repository.size(), "three entries");
        eq(cancelled, repository.history().get(0), "newest first");
        eq(done, repository.history().get(2), "oldest last");

        eq(1, repository.history(1).size(), "limit honoured");
        eq(cancelled, repository.history(1).get(0), "limit keeps newest");
        eq(0, repository.history(0).size(), "zero limit");
        expect(IllegalArgumentException.class,
                () -> repository.history(-1), "negative limit rejected");

        check(repository.contains(done.duplicateKey()), "done key present");
        check(!repository.contains(failed.duplicateKey()), "failed key absent");
        expect(IllegalArgumentException.class,
                () -> repository.contains(" "), "blank key rejected");

        repository.save(doneTask("fourth", "fourth"));
        eq(3, repository.size(), "capacity enforced");
        check(!repository.contains(done.duplicateKey()), "oldest entry evicted");

        repository.clear();
        eq(0, repository.size(), "clear empties history");
        eq(InMemoryDownloadRepository.DEFAULT_CAPACITY,
                new InMemoryDownloadRepository().capacity(), "default capacity");
        expect(IllegalArgumentException.class,
                () -> new InMemoryDownloadRepository(0), "zero capacity rejected");

        InMemoryDownloadRepository snapshots = new InMemoryDownloadRepository(5);
        DownloadTask failedSnapshot = new DownloadTask("snap-failed", item("snap"));
        failedSnapshot.start();
        failedSnapshot.fail(DownloadError.NETWORK);
        snapshots.save(failedSnapshot);

        DownloadTask stored = snapshots.history().get(0);
        check(stored != failedSnapshot, "history returns an independent copy");
        check(stored.retry(), "a returned copy can be inspected and retried");
        eq(DownloadState.FAILED, snapshots.history().get(0).state(),
                "mutating a returned copy does not change the repository");

        check(failedSnapshot.retry(), "the original failed task can be retried");
        failedSnapshot.start();
        failedSnapshot.succeed(DownloadResult.success("/gallery/snap.jpg", 1L));
        eq(DownloadState.FAILED, snapshots.history().get(0).state(),
                "mutating the saved task does not change the repository");
        check(!snapshots.contains(failedSnapshot.duplicateKey()),
                "the stored snapshot is still a failure, not a duplicate");
    }

    private static void storagePlans() {
        MediaItem photo = photo("p1", "https://cdn.example/a.jpg", "alice");
        StoragePlan photoPlan = StoragePlan.forItem(photo, 1700000000000L);
        eq(DownloadKind.PHOTO, photoPlan.kind(), "photo kind");
        eq(StoragePlan.IMAGE_DIRECTORY, photoPlan.relativeDirectory(), "photo directory");
        eq("hmx_alice_p1_photo_1700000000000.jpg", photoPlan.fileName(), "photo name");
        eq("image/jpeg", photoPlan.mimeType(), "photo mime");
        check(photoPlan.pending(), "MediaStore insert is pending");
        eq(photoPlan.fileName() + ".part", photoPlan.temporaryFileName(), "temp name");
        check(photoPlan.isImage() && !photoPlan.isVideo() && !photoPlan.isAudio(),
                "photo classification");

        MediaItem video = video("v1", "https://cdn.example/a.mp4", "alice");
        StoragePlan videoPlan = StoragePlan.forItem(video, 10L);
        eq(StoragePlan.VIDEO_DIRECTORY, videoPlan.relativeDirectory(), "video directory");
        eq("video/mp4", videoPlan.mimeType(), "video mime");
        check(videoPlan.isVideo(), "video classification");

        MediaItem audio = new MediaItem.Builder(DownloadKind.AUDIO)
                .id("a1")
                .url("https://cdn.example/a.m4a")
                .mimeType("audio/mp4")
                .owner("alice")
                .build();
        StoragePlan audioPlan = StoragePlan.forItem(audio, 10L);
        eq(StoragePlan.AUDIO_DIRECTORY, audioPlan.relativeDirectory(), "audio directory");
        eq("m4a", audioPlan.fileName().substring(audioPlan.fileName().lastIndexOf('.') + 1),
                "audio extension");
        check(audioPlan.isAudio(), "audio classification");

        MediaItem cover = new MediaItem.Builder(DownloadKind.COVER)
                .id("cov")
                .url("https://cdn.example/cover.jpg")
                .build();
        eq(StoragePlan.IMAGE_DIRECTORY,
                StoragePlan.forItem(cover, 1L).relativeDirectory(), "cover is an image");

        MediaItem carousel = new MediaItem.Builder(DownloadKind.CAROUSEL)
                .addChild(photo).build();
        expect(IllegalArgumentException.class,
                () -> StoragePlan.forItem(carousel, 1L), "carousel must be expanded");
        expect(IllegalArgumentException.class,
                () -> StoragePlan.forItem(photo, -1L), "negative timestamp rejected");

        eq("/storage/emulated/0/" + StoragePlan.IMAGE_DIRECTORY + "/"
                        + photoPlan.fileName(),
                photoPlan.pathUnder("/storage/emulated/0"), "absolute path");
        eq("/" + StoragePlan.IMAGE_DIRECTORY + "/" + photoPlan.fileName(),
                photoPlan.pathUnder("/"), "root path");
        eq("/sdcard/" + StoragePlan.IMAGE_DIRECTORY + "/" + photoPlan.fileName(),
                photoPlan.pathUnder("/sdcard/"), "trailing slash normalised");
        expect(IllegalArgumentException.class,
                () -> photoPlan.pathUnder("/sdcard/../etc"), "traversal rejected");
        expect(IllegalArgumentException.class,
                () -> photoPlan.pathUnder(" "), "blank root rejected");
        eq(-1L, photoPlan.expectedBytes(), "unknown size preserved");

        MediaItem sized = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("sized")
                .url("https://cdn.example/sized.jpg")
                .sizeBytes(4096L)
                .build();
        eq(4096L, StoragePlan.forItem(sized, 1L).expectedBytes(), "size preserved");
    }

    private static void mimeRoundTrip() {
        String[] mimes = {
                "image/jpeg", "image/png", "image/webp", "image/heic", "image/gif",
                "video/mp4", "video/quicktime", "video/webm",
                "audio/mp4", "audio/mpeg", "audio/ogg", "audio/aac"
        };
        for (String mime : mimes) {
            String extension = MediaNaming.extensionForMime(mime);
            check(!"bin".equals(extension), "known mime maps: " + mime);
            check(MediaNaming.mimeForExtension(extension) != null,
                    "extension maps back: " + extension);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static MediaItem item(String id) {
        return photo(id, "https://cdn.example/" + id + ".jpg", "alice");
    }

    private static MediaItem photo(String id, String url, String owner) {
        return new MediaItem.Builder(DownloadKind.PHOTO)
                .id(id)
                .url(url)
                .owner(owner)
                .build();
    }

    private static MediaItem video(String id, String url, String owner) {
        return new MediaItem.Builder(DownloadKind.VIDEO)
                .id(id)
                .url(url)
                .owner(owner)
                .build();
    }

    private static DownloadTask doneTask(String id, String mediaId) {
        DownloadTask task = new DownloadTask(id, item(mediaId));
        task.start();
        task.succeed(DownloadResult.success("/gallery/" + mediaId + ".jpg", 1L));
        return task;
    }

    private static DownloadTask failedTask(String id, String mediaId) {
        DownloadTask task = new DownloadTask(id, item(mediaId));
        task.start();
        task.fail(DownloadError.STORAGE);
        return task;
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failures.add(name + ": " + failure);
            System.out.println("FAIL " + name + ": " + failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=" + expected
                    + ", actual=" + actual + ')');
        }
    }

    private static <T extends Throwable> void expect(
            Class<T> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + " (wrong exception: " + thrown + ')');
        }
        throw new AssertionError(message + " (no exception thrown)");
    }

    private DownloaderCoreTest() {
    }
}
