package com.feurstagram.extension.downloader;

import java.lang.reflect.Method;

/**
 * The story viewer's media.
 *
 * A story is only downloadable while it is live: an expired story has no media
 * left to fetch, and the adapter says so instead of offering a dead URL.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class StoryMediaAdapter implements MediaSource {

    private final FeedMediaAdapter delegate = new FeedMediaAdapter();

    @Override
    public String displayName() {
        return "Story";
    }

    @Override
    public MediaItem.Offer resolve(Object model) {
        MediaItem.Offer offer = new MediaItem.Offer(
                MediaKind.fromIgMediaType(MediaResolver.mediaType(model)));
        if (model == null) {
            offer.unavailable(MediaItem.Variant.PHOTO, "no story");
            return offer;
        }

        if (isExpired(model)) {
            offer.unavailable(MediaItem.Variant.PHOTO, "this story has expired");
            offer.unavailable(MediaItem.Variant.VIDEO, "this story has expired");
            return offer;
        }

        MediaItem.Offer resolved = delegate.resolve(model);
        offer.addAll(resolved.items());
        offer.unavailable().putAll(resolved.unavailable());
        if (offer.isEmpty()) {
            offer.unavailable(MediaItem.Variant.PHOTO, "this story exposes no media URL");
        }
        return offer;
    }

    /**
     * Whether the story's media is gone. Instagram stores the expiry on the
     * story item; when the model exposes no expiry at all the story is treated
     * as live, because refusing to download a live story would be worse than
     * offering a URL that turns out to be dead (the download then fails with a
     * clear error).
     */
    private boolean isExpired(Object model) {
        Long expiringAt = expiringAt(model);
        if (expiringAt == null || expiringAt <= 0) return false;
        return expiringAt <= System.currentTimeMillis();
    }

    /** The story's expiry timestamp, or null when the model exposes none. */
    static Long expiringAt(Object model) {
        for (String name : new String[]{"getExpiringAt", "getExpiringAtTime"}) {
            try {
                Method method = model.getClass().getMethod(name);
                Object value = method.invoke(model);
                if (value instanceof Number) return ((Number) value).longValue();
            } catch (Throwable ignored) {
                // Not present: try the next name.
            }
        }
        return null;
    }
}
