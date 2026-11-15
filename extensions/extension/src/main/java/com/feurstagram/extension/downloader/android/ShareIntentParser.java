package com.feurstagram.extension.downloader.android;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.feurstagram.extension.downloader.core.SharedStreams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates the streams arriving from an {@code ACTION_SEND} intent.
 *
 * <p>Only {@code content://} URIs carrying an image, video or audio MIME type
 * are accepted: the share component is exported, so anything else is rejected
 * before it can reach the importer. Stream selection supports both
 * {@code EXTRA_STREAM} and {@code ClipData} in any combination (see
 * {@link SharedStreams}); the count and the per-item size are bounded so a
 * hostile intent cannot fill the cache or the queue.
 */
final class ShareIntentParser {

    /** Largest single share accepted, before MediaStore is touched. */
    static final long MAX_ITEM_BYTES = 512L * 1024L * 1024L;

    /** The stream cap, re-exported for the share activity's error message. */
    static final int MAX_ITEMS = SharedStreams.MAX_ITEMS;

    static final class Payload {
        final Uri uri;
        final String mimeType;

        Payload(Uri uri, String mimeType) {
            this.uri = uri;
            this.mimeType = mimeType;
        }
    }

    private ShareIntentParser() {
    }

    static List<Payload> parse(Context context, Intent intent) {
        if (context == null) {
            throw new IllegalArgumentException("no context");
        }
        if (intent == null) {
            throw new IllegalArgumentException("no share intent");
        }
        String action = intent.getAction();
        boolean single = Intent.ACTION_SEND.equals(action);
        boolean multiple = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (!single && !multiple) {
            throw new IllegalArgumentException("unsupported share action");
        }

        List<String> extraStreams = new ArrayList<String>();
        if (single) {
            Uri stream;
            try {
                stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("unusable shared stream");
            }
            if (stream != null) {
                extraStreams.add(stream.toString());
            }
        } else {
            ArrayList<Uri> extra;
            try {
                extra = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("unusable shared streams");
            }
            if (extra != null) {
                for (Uri stream : extra) {
                    if (stream != null) {
                        extraStreams.add(stream.toString());
                    }
                }
            }
        }

        // Some senders only put the URI in ClipData (for example when the
        // extra was dropped by a relay), so both sources are merged.
        List<String> clipStreams = new ArrayList<String>();
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            int count = clipData.getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    clipStreams.add(uri.toString());
                }
            }
        }

        List<String> selected;
        try {
            selected = SharedStreams.select(extraStreams, clipStreams);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "too many shared items: " + e.getMessage());
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("nothing to share");
        }

        ContentResolver resolver = context.getContentResolver();
        String declared = normalize(intent.getType());
        List<Payload> payloads = new ArrayList<Payload>(selected.size());
        for (String value : selected) {
            Uri uri;
            try {
                uri = Uri.parse(value);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("unusable shared stream");
            }
            if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                throw new IllegalArgumentException("only content streams can be shared");
            }
            String mimeType;
            try {
                mimeType = normalize(resolver.getType(uri));
            } catch (RuntimeException e) {
                // No read grant yet, or the provider refused: fall back to the
                // intent's declared type, which the grant usually covers.
                mimeType = null;
            }
            if (isGeneric(mimeType)) {
                mimeType = declared;
            }
            if (!isSupportedMedia(mimeType)) {
                throw new IllegalArgumentException(
                        "only images, videos and audio can be shared");
            }
            payloads.add(new Payload(uri, mimeType));
        }
        return payloads;
    }

    /** Whether the MIME type can be handled at all. */
    static boolean isSupportedMedia(String mimeType) {
        return mimeType != null
                && (mimeType.startsWith("image/") || mimeType.startsWith("video/")
                        || mimeType.startsWith("audio/"));
    }

    /** {@code null} and the catch-all type cannot be trusted as a selector. */
    private static boolean isGeneric(String mimeType) {
        return mimeType == null || "application/octet-stream".equals(mimeType);
    }

    private static String normalize(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        if (parameter >= 0) {
            normalized = normalized.substring(0, parameter).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
