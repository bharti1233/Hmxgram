package com.feurstagram.extension.downloader;

import java.util.List;

/**
 * The feed's media: a photo, a video, or a carousel of children.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class FeedMediaAdapter implements MediaSource {

    @Override
    public String displayName() {
        return "Post";
    }

    @Override
    public MediaItem.Offer resolve(Object model) {
        MediaItem.Offer offer = new MediaItem.Offer(MediaKind.fromIgMediaType(MediaResolver.mediaType(model)));
        if (model == null) {
            offer.unavailable(MediaItem.Variant.PHOTO, "no media");
            return offer;
        }

        int type = MediaResolver.mediaType(model);
        MediaKind kind = MediaKind.fromIgMediaType(type);
        String id = MediaResolver.mediaId(model);
        int width = MediaResolver.width(model);
        int height = MediaResolver.height(model);

        if (kind == MediaKind.CAROUSEL) {
            List<Object> children = MediaResolver.carouselChildren(model);
            if (children.isEmpty()) {
                offer.unavailable(MediaItem.Variant.PHOTO, "the carousel does not expose its items");
                return offer;
            }
            for (Object child : children) {
                addSingle(offer, child, id, width, height, true);
            }
            if (offer.isEmpty()) {
                offer.unavailable(MediaItem.Variant.PHOTO, "no downloadable item in this carousel");
            }
            return offer;
        }

        addSingle(offer, model, id, width, height, false);
        if (offer.isEmpty()) {
            offer.unavailable(MediaItem.Variant.PHOTO, "this post exposes no media URL");
        }
        return offer;
    }

    /** Adds the photo and video variants of one media model to the offer. */
    void addSingle(MediaItem.Offer offer, Object model, String parentId, int width, int height,
                   boolean carouselChild) {
        String id = MediaResolver.mediaId(model);
        if (id == null) id = parentId;
        int type = MediaResolver.mediaType(model);
        MediaKind kind = MediaKind.fromIgMediaType(type);

        if (kind == MediaKind.VIDEO) {
            String video = MediaResolver.videoUrl(model);
            if (video != null) {
                offer.add(MediaItem.video(id, video, false, width, height));
                if (MediaResolver.hasAudio(model)) {
                    // The audio is a track inside the same file: it is extracted
                    // locally, never fetched from another source.
                    offer.add(MediaItem.video(id, video, true, width, height));
                }
            }
            String cover = MediaResolver.imageUrl(model);
            if (cover != null) {
                offer.add(MediaItem.cover(id, cover, width, height));
            }
            if (video == null && cover == null) {
                offer.unavailable(MediaItem.Variant.VIDEO, "this video exposes no media URL");
            }
            return;
        }

        String image = MediaResolver.imageUrl(model);
        if (image != null) {
            offer.add(MediaItem.photo(id, image, width, height));
        } else {
            offer.unavailable(MediaItem.Variant.PHOTO, "this item exposes no image URL");
        }
    }
}
