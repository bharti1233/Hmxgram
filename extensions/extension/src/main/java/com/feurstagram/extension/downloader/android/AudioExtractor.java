package com.feurstagram.extension.downloader.android;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Copies the first audio track of a downloaded video into an {@code .m4a}
 * container without re-encoding.
 *
 * <p>The share-target critical path never depends on this: a photo or a video
 * is published even if extraction is unsupported on a device. Extraction is
 * only attempted for an {@link com.feurstagram.extension.downloader.core.DownloadKind#AUDIO}
 * task whose source is a container {@link com.feurstagram.extension.downloader.core.AudioSourcePolicy}
 * accepts; an empty or unsupported result is reported as {@code false} so the
 * task fails with {@code unsupported} instead of leaving a silent file.
 *
 * <p>No Instagram class or method is referenced.
 */
final class AudioExtractor {

    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;

    private AudioExtractor() {
    }

    /**
     * Extracts audio from {@code input} into {@code output}.
     *
     * @return {@code false} when the video has no audio track
     * @throws IOException when the files cannot be read or muxed
     */
    static boolean extract(File input, File output) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("an extraction needs an input and an output");
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = candidate;
                    break;
                }
            }
            if (audioTrack < 0 || format == null) {
                return false;
            }
            extractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(format);
            muxer.start();

            int capacity = DEFAULT_BUFFER_SIZE;
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                int declared = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                if (declared > capacity) {
                    capacity = declared;
                }
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long samples = 0L;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outputTrack, buffer, info);
                samples++;
                if (!extractor.advance()) {
                    break;
                }
            }
            // An empty muxer output is a silent, broken file: report failure
            // so the caller deletes it instead of publishing it.
            return samples > 0;
        } catch (RuntimeException e) {
            throw new IOException("audio extraction failed", e);
        } finally {
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
                // Already released or the platform rejected it.
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException ignored) {
                    // Nothing was muxed; the output is discarded by the caller.
                }
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                    // Already released.
                }
            }
        }
    }
}
