package com.feurstagram.extension.downloader;

import android.app.Activity;

/**
 * Which surface the user is looking at, and therefore which adapter resolves
 * the media they can download.
 *
 * The surface is decided from the screen that is in front of the user, not
 * from a class name that changes with every Instagram release: the story and
 * reels viewers are the screens Instagram opens on top of the feed, and the
 * feed is everything else.
 */
final class Surface {

    private static final FeedMediaAdapter FEED = new FeedMediaAdapter();
    private static final StoryMediaAdapter STORY = new StoryMediaAdapter();
    private static final ReelMediaAdapter REEL = new ReelMediaAdapter();

    private Surface() {
    }

    /**
     * The adapter for a screen.
     *
     * The reels viewer is its own screen; the story viewer is a screen on top
     * of the feed, so the story rules — which also refuse media that has
     * expired — are the safe ones everywhere else.
     */
    static MediaSource forActivity(Activity activity) {
        if (activity == null) return STORY;
        String name = activity.getClass().getName().toLowerCase(java.util.Locale.US);
        if (name.contains("clips")) return REEL;
        return STORY;
    }
}
