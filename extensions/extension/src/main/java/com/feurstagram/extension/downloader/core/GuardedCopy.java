package com.feurstagram.extension.downloader.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a stream while honouring a {@link CancellationSignal}, with a
 * rollback hook for the bytes already written elsewhere.
 *
 * <p>This is the publish-side guard: the MediaStore row exists (pending) before
 * the copy starts, so a cancelled or failed copy must delete that row instead
 * of leaving an empty gallery entry. The same rule applies to any sink with a
 * visible partial result.
 *
 * <p>The discard hook runs exactly once, before the exception leaves the
 * method, for cancellation, I/O failure and runtime failure. It never runs on
 * success. The caller still owns both streams.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class GuardedCopy {

    /** Size of the copy buffer. */
    public static final int BUFFER_SIZE = 64 * 1024;

    /** Rollback for a partially written destination. */
    public interface Discard {
        void discard();
    }

    private GuardedCopy() {
    }

    /**
     * Copies {@code in} to {@code out} and returns the number of bytes.
     *
     * @throws DownloadCancelledException when cancellation was requested before
     *         or during the copy
     * @throws IOException when the read, write or rollback path fails
     */
    public static long copy(InputStream in, OutputStream out,
            CancellationSignal signal, Discard discard) throws IOException {
        if (in == null || out == null) {
            throw new IllegalArgumentException("copy needs an input and an output");
        }
        if (signal == null) {
            throw new IllegalArgumentException("copy needs a cancellation signal");
        }
        try {
            signal.throwIfCancelled();
            long total = 0L;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                signal.throwIfCancelled();
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            signal.throwIfCancelled();
            return total;
        } catch (IOException e) {
            discardOnce(discard);
            throw e;
        } catch (RuntimeException e) {
            discardOnce(discard);
            throw e;
        }
    }

    private static void discardOnce(Discard discard) {
        if (discard == null) {
            return;
        }
        try {
            discard.discard();
        } catch (RuntimeException ignored) {
            // The rollback is best effort; the original failure is what the
            // caller must see.
        }
    }
}
