package com.feurstagram.extension.downloader;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Extracts the audio track of a downloaded video into its own file.
 *
 * The track is copied out of the video that was already downloaded, with the
 * platform's own media stack — no re-encoding, no third-party tool and
 * nothing leaves the device.
 */
final class AudioExtractor {

    private AudioExtractor() {
    }

    /**
     * Writes the audio track of {@code source} into the gallery.
     *
     * @param context the context
     * @param source  the downloaded video
     * @param name    the file name to use
     * @return the gallery location, or null when there is no audio track
     */
    static String extract(Context context, File source, String name) {
        if (context == null || source == null || !source.exists()) return null;

        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        File output = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) return null;

            output = File.createTempFile("hmx-insta-audio-", ".m4a", context.getCacheDir());
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(format);
            muxer.start();

            extractor.selectTrack(track);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            long presentation = 0;
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (info.size > 0) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = presentation;
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(outTrack, buffer, info);
                    presentation += (long) size * 1000000L / (sampleRate * channels * 2L);
                }
                extractor.advance();
            }

            muxer.stop();
            muxer.release();
            muxer = null;
            extractor.release();
            extractor = null;

            MediaItem audio = MediaItem.video(null, null, true, 0, 0);
            String saved = MediaStoreWriter.writeAudio(context, output, audio, name);
            return saved;
        } catch (Throwable t) {
            return null;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // Nothing else to do.
                }
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                    // Nothing else to do.
                }
            }
            if (output != null && output.exists()) output.delete();
        }
    }
}
