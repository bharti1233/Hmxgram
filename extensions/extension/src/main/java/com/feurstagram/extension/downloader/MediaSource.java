package com.feurstagram.extension.downloader;

/**
 * A source of downloadable media: the feed, the story viewer or the reels
 * viewer. Each adapter turns one Instagram media model into the set of items
 * that can be downloaded from it.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public interface MediaSource {

    /**
     * The offer for one media model.
     *
     * @param model an Instagram media model, or null
     * @return what can be downloaded; never null. An empty offer carries the
     *         reason in {@link MediaItem.Offer#unavailable()}.
     */
    MediaItem.Offer resolve(Object model);

    /** The name shown in the UI, e.g. "Story". */
    String displayName();
}
