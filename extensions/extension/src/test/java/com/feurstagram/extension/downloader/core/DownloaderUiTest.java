package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.List;

/**
 * M3 tests: the projection the downloader screen renders and the history
 * deletion it offers.
 *
 * <p>The screen must never show a success that did not happen, must offer
 * exactly the actions the engine allows, and must report progress honestly
 * when the source did not announce a size.
 */
public final class DownloaderUiTest {

    private DownloaderUiTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M3 test group and returns the number of failures. */
    public static int run() {
        List<String> failures = new ArrayList<String>();
        int passed = 0;

        passed += check("row projection", new Runnable() {
            @Override
            public void run() {
                rowProjection();
            }
        }, failures);
        passed += check("row actions", new Runnable() {
            @Override
            public void run() {
                rowActions();
            }
        }, failures);
        passed += check("progress reporting", new Runnable() {
            @Override
            public void run() {
                progressReporting();
            }
        }, failures);
        passed += check("history deletion", new Runnable() {
            @Override
            public void run() {
                historyDeletion();
            }
        }, failures);
        passed += check("byte formatting", new Runnable() {
            @Override
            public void run() {
                byteFormatting();
            }
        }, failures);

        System.out.println("PASS " + passed + " downloader-ui tests");
        if (!failures.isEmpty()) {
            for (String failure : failures) {
                System.out.println("FAIL " + failure);
            }
            return failures.size();
        }
        return 0;
    }

    private static int check(String name, Runnable test, List<String> failures) {
        try {
            test.run();
            System.out.println("PASS " + name);
            return 1;
        } catch (Throwable failure) {
            failures.add(name + ": " + failure);
            System.out.println("FAIL " + name + ": " + failure);
            return 0;
        }
    }

    private static void rowProjection() {
        MediaItem item = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("share-abc")
                .url("https://cdn.example/abc.jpg")
                .mimeType("image/jpeg")
                .sizeBytes(2048L)
                .owner("alice")
                .build();

        DownloadTask queued = new DownloadTask("task-1", item);
        DownloaderRow row = DownloaderRow.from(queued, 0L, 2048L);
        check("task-1".equals(row.id()), "the row keeps the task id");
        check(DownloadState.QUEUED == row.state(), "the row keeps the state");
        check(DownloadKind.PHOTO == row.kind(), "the row keeps the kind");
        check(row.title() != null && !row.title().trim().isEmpty(),
                "the row has a title");
        check(row.title().endsWith(".jpg"), "the title is the gallery file name");
        check("Pictures/HMXInsta".equals(row.location()),
                "the row knows the gallery directory");
        check(row.savedPath() == null, "a queued row has no saved path");
        check(!row.isVerified(), "a queued row is not verified");
        check("Queued".equals(row.statusLabel()), "the queued label");

        // A finished task with a published path is the only verified state.
        DownloadTask done = new DownloadTask("task-2", item);
        done.start();
        done.succeed(DownloadResult.success("Pictures/HMXInsta/abc.jpg", 2048L));
        DownloaderRow doneRow = DownloaderRow.from(done, 2048L, 2048L);
        check(doneRow.isVerified(), "a finished task with a path is verified");
        check("Saved".equals(doneRow.statusLabel()), "the saved label");
        check("Pictures/HMXInsta/abc.jpg".equals(doneRow.savedPath()),
                "the row carries the published path");

        // A finished task without a path must never be presented as a success.
        // The engine refuses to build such a result in the first place, and
        // the history codec rejects one on decode: a success always carries
        // the path it was published under.
        try {
            DownloadResult.success("   ", 0L);
            throw new AssertionError("a success without a path must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected: the engine does not allow an unverifiable success.
        }

        // A failure carries the engine's own message.
        DownloadTask failed = new DownloadTask("task-4", item);
        failed.start();
        failed.fail(DownloadError.NETWORK);
        DownloaderRow failedRow = DownloaderRow.from(failed, 10L, 2048L);
        check(DownloadError.NETWORK == failedRow.error(), "the row keeps the error");
        check(failedRow.statusLabel().contains("Network"),
                "the failure message is shown");

        // A cancelled task says so.
        DownloadTask cancelled = new DownloadTask("task-5", item);
        cancelled.cancel();
        DownloaderRow cancelledRow = DownloaderRow.from(cancelled, 0L, -1L);
        check("Cancelled".equals(cancelledRow.statusLabel()), "the cancelled label");

        // A null task is refused.
        try {
            DownloaderRow.from(null, 0L, 0L);
            throw new AssertionError("a null task must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void rowActions() {
        MediaItem item = new MediaItem.Builder(DownloadKind.VIDEO)
                .id("share-def")
                .url("https://cdn.example/def.mp4")
                .mimeType("video/mp4")
                .owner("alice")
                .build();

        DownloadTask queued = new DownloadTask("task-1", item);
        DownloaderRow queuedRow = DownloaderRow.from(queued, 0L, 100L);
        check(queuedRow.canCancel(), "a queued task can be cancelled");
        check(!queuedRow.canRetry(), "a queued task cannot be retried");
        check(!queuedRow.canDelete(), "a queued task cannot be deleted");

        DownloadTask running = new DownloadTask("task-2", item);
        running.start();
        DownloaderRow runningRow = DownloaderRow.from(running, 10L, 100L);
        check(runningRow.canCancel(), "a running task can be cancelled");
        check(runningRow.isActive(), "a running task is active");

        DownloadTask failed = new DownloadTask("task-3", item);
        failed.start();
        failed.fail(DownloadError.NETWORK);
        DownloaderRow failedRow = DownloaderRow.from(failed, 0L, 100L);
        check(failedRow.canRetry(), "a recoverable failure can be retried");
        check(failedRow.canDelete(), "a finished entry can be deleted");
        check(!failedRow.canCancel(), "a failed task cannot be cancelled");

        DownloadTask permanent = new DownloadTask("task-4", item);
        permanent.start();
        permanent.fail(DownloadError.STORAGE);
        DownloaderRow permanentRow = DownloaderRow.from(permanent, 0L, 100L);
        check(!permanentRow.canRetry(), "a permanent failure cannot be retried");

        DownloadTask done = new DownloadTask("task-5", item);
        done.start();
        done.succeed(DownloadResult.success("Movies/HMXInsta/def.mp4", 100L));
        DownloaderRow doneRow = DownloaderRow.from(done, 100L, 100L);
        check(!doneRow.canRetry(), "a finished download cannot be retried");
        check(doneRow.canDelete(), "a finished download can be deleted");
        check(!doneRow.canCancel(), "a finished download cannot be cancelled");

        DownloadTask cancelled = new DownloadTask("task-6", item);
        cancelled.cancel();
        DownloaderRow cancelledRow = DownloaderRow.from(cancelled, 0L, 100L);
        check(!cancelledRow.canRetry(), "a cancelled task is not offered a retry");
        check(cancelledRow.canDelete(), "a cancelled entry can be deleted");

        // The attempt budget is honoured: the last attempt cannot be retried.
        DownloadTask exhausted = new DownloadTask("task-7", item, 1, 0L);
        exhausted.start();
        exhausted.fail(DownloadError.NETWORK);
        DownloaderRow exhaustedRow = DownloaderRow.from(exhausted, 0L, 100L);
        check(!exhaustedRow.canRetry(), "no attempts left means no retry");
        check(exhaustedRow.attempts() >= exhaustedRow.maxAttempts(),
                "the row shows the attempts");
    }

    private static void progressReporting() {
        MediaItem item = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("share-abc")
                .url("https://cdn.example/abc.jpg")
                .mimeType("image/jpeg")
                .owner("alice")
                .build();

        DownloadTask task = new DownloadTask("task-1", item);
        task.start();

        DownloaderRow half = DownloaderRow.from(task, 512L, 1024L);
        check(half.progressPercent() == 50, "half of the bytes is 50 percent");
        check("Downloading — 50%".equals(half.statusLabel()),
                "the progress label shows the percentage");

        DownloaderRow done = DownloaderRow.from(task, 1024L, 1024L);
        check(done.progressPercent() == 100, "a complete transfer is 100 percent");

        DownloaderRow over = DownloaderRow.from(task, 2048L, 1024L);
        check(over.progressPercent() == 100, "the percentage never exceeds 100");

        // A source without an announced size: no percentage, but the bytes
        // are still reported.
        DownloaderRow unknown = DownloaderRow.from(task, 4096L, -1L);
        check(unknown.progressPercent() == DownloaderRow.UNKNOWN_PERCENT,
                "an unknown size has no percentage");
        check(unknown.statusLabel().startsWith("Downloading —"),
                "an unknown size still shows a status");
        check(unknown.statusLabel().contains("4.0 KB"),
                "an unknown size shows the bytes: " + unknown.statusLabel());
        check(DownloaderRow.UNKNOWN_BYTES == unknown.totalBytes(),
                "the row records the unknown total");

        // A zero total is unknown, not a division by zero.
        DownloaderRow zero = DownloaderRow.from(task, 10L, 0L);
        check(zero.progressPercent() == DownloaderRow.UNKNOWN_PERCENT,
                "a zero total is unknown");
    }

    private static void historyDeletion() {
        MediaItem item = new MediaItem.Builder(DownloadKind.PHOTO)
                .id("share-abc")
                .url("https://cdn.example/abc.jpg")
                .mimeType("image/jpeg")
                .owner("alice")
                .build();

        DownloadTask done = new DownloadTask("task-1", item);
        done.start();
        done.succeed(DownloadResult.success("Pictures/HMXInsta/abc.jpg", 10L));
        DownloadTask failed = new DownloadTask("task-2", item);
        failed.start();
        failed.fail(DownloadError.NETWORK);

        InMemoryDownloadRepository repository = new InMemoryDownloadRepository();
        repository.save(done);
        repository.save(failed);
        check(repository.size() == 2, "two entries are remembered");

        check(repository.delete("task-1"), "an existing entry is removed");
        check(repository.size() == 1, "the entry is gone");
        check(!repository.contains(MediaNaming.duplicateKey(item)),
                "the duplicate key is released again");

        check(!repository.delete("task-1"), "removing it twice reports nothing");
        check(!repository.delete("missing"), "an unknown id removes nothing");
        check(repository.size() == 1, "the other entry survives");

        // The active task cannot be deleted: only a finished task is stored.
        try {
            repository.save(new DownloadTask("task-3", item));
            throw new AssertionError("an active task must not be stored");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            repository.delete("  ");
            throw new AssertionError("a blank id must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void byteFormatting() {
        check("0 B".equals(DownloaderRow.formatBytes(0L)), "zero bytes");
        check("512 B".equals(DownloaderRow.formatBytes(512L)), "bytes");
        check("1.0 KB".equals(DownloaderRow.formatBytes(1024L)), "kilobytes");
        check("1.0 MB".equals(DownloaderRow.formatBytes(1024L * 1024L)), "megabytes");
        check("1.5 GB".equals(DownloaderRow.formatBytes(
                (long) (1.5 * 1024 * 1024 * 1024))), "gigabytes");
        check("0 B".equals(DownloaderRow.formatBytes(-1L)), "a negative count");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
