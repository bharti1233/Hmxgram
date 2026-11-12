package com.feurstagram.extension.downloader;

/**
 * The reels viewer's media: the video, its audio track and its cover.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class ReelMediaAdapter implements MediaSource {

    @Override
    public String displayName() {
        return "Reel";
    }

    @Override
    public MediaItem.Offer resolve(Object model) {
        MediaItem.Offer offer = new MediaItem.Offer(
                MediaKind.fromIgMediaType(MediaResolver.mediaType(model)));
        if (model == null) {
            offer.unavailable(MediaItem.Variant.VIDEO, "no reel");
            return offer;
        }

        String id = MediaResolver.mediaId(model);
        int width = MediaResolver.width(model);
        int height = MediaResolver.height(model);

        String video = MediaResolver.videoUrl(model);
        if (video != null) {
            offer.add(MediaItem.video(id, video, false, width, height));
            if (MediaResolver.hasAudio(model)) {
                offer.add(MediaItem.video(id, video, true, width, height));
            }
        } else {
            offer.unavailable(MediaItem.Variant.VIDEO, "this reel exposes no video URL");
        }

        String cover = MediaResolver.imageUrl(model);
        if (cover != null) {
            offer.add(MediaItem.cover(id, cover, width, height));
        } else {
            offer.unavailable(MediaItem.Variant.COVER, "this reel exposes no cover image");
        }

        if (offer.isEmpty()) {
            offer.unavailable(MediaItem.Variant.VIDEO, "this reel exposes no media URL");
        }
        return offer;
    }
}
