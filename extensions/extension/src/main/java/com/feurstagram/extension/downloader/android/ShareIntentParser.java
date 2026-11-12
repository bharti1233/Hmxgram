package com.feurstagram.extension.downloader.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates the streams arriving from an {@code ACTION_SEND} intent.
 *
 * <p>Only {@code content://} URIs carrying an image or video MIME type are
 * accepted: the share component is exported, so anything else is rejected
 * before it can reach the importer. The count and the per-item size are
 * bounded so a hostile intent cannot fill the cache or the queue.
 */
final class ShareIntentParser {

    static final int MAX_ITEMS = 10;

    /** Largest single share accepted, before MediaStore is touched. */
    static final long MAX_ITEM_BYTES = 512L * 1024L * 1024L;

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

        List<Uri> streams = new ArrayList<Uri>();
        if (single) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                streams.add(stream);
            }
        } else {
            ArrayList<Uri> extra = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extra != null) {
                streams.addAll(extra);
            }
        }
        if (streams.isEmpty()) {
            throw new IllegalArgumentException("nothing to share");
        }
        if (streams.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many shared items");
        }

        ContentResolver resolver = context.getContentResolver();
        String declared = normalize(intent.getType());
        List<Payload> payloads = new ArrayList<Payload>(streams.size());
        for (Uri uri : streams) {
            if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                throw new IllegalArgumentException("only content streams can be shared");
            }
            String mimeType = normalize(resolver.getType(uri));
            if (mimeType == null) {
                mimeType = declared;
            }
            if (!isImageOrVideo(mimeType)) {
                throw new IllegalArgumentException("only images and videos can be shared");
            }
            payloads.add(new Payload(uri, mimeType));
        }
        return payloads;
    }

    static boolean isImageOrVideo(String mimeType) {
        return mimeType != null
                && (mimeType.startsWith("image/") || mimeType.startsWith("video/"));
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
