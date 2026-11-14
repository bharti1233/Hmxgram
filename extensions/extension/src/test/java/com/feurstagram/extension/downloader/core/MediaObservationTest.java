package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * M4 tests: reading an Instagram-shaped media model with the type-based
 * reader, and keeping the observations in a bounded registry.
 *
 * <p>The fake models below have the same shape as the real ones: an image
 * info object, a video info object, a list of children and a user. Their
 * accessor names are deliberately meaningless, because the reader must never
 * depend on a name.
 */
public final class MediaObservationTest {

    private MediaObservationTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M4 test group and returns the number of failures. */
    public static int run() {
        int failures = 0;
        failures += group("photo model", new Runnable() {
            @Override
            public void run() {
                photoModel();
            }
        });
        failures += group("video model", new Runnable() {
            @Override
            public void run() {
                videoModel();
            }
        });
        failures += group("carousel model", new Runnable() {
            @Override
            public void run() {
                carouselModel();
            }
        });
        failures += group("model rejection", new Runnable() {
            @Override
            public void run() {
                modelRejection();
            }
        });
        failures += group("observation to item", new Runnable() {
            @Override
            public void run() {
                observationToItem();
            }
        });
        failures += group("registry bounds", new Runnable() {
            @Override
            public void run() {
                registryBounds();
            }
        });
        failures += group("non-media list fallback", new Runnable() {
            @Override
            public void run() {
                nonMediaListFallback();
            }
        });
        if (failures == 0) {
            System.out.println("PASS 7 downloader-observation tests");
        }
        return failures;
    }

    private static int group(String name, Runnable test) {
        try {
            test.run();
            System.out.println("PASS " + name);
            return 0;
        } catch (Throwable failure) {
            System.out.println("FAIL " + name + ": " + failure);
            return 1;
        }
    }

    /** The image info object of the real model. */
    public static final class ImageInfo {
        private final String url;
        private final int width;

        ImageInfo(String url, int width) {
            this.url = url;
            this.width = width;
        }

        public String url() {
            return url;
        }

        public int width() {
            return width;
        }
    }

    /** The video info object of the real model. */
    public static final class VideoInfo {
        private final String url;

        VideoInfo(String url) {
            this.url = url;
        }

        public String url() {
            return url;
        }
    }

    /** A fake media model with the shape of Instagram's. */
    public static final class FakeMedia {
        private final ImageInfo image;
        private final VideoInfo video;
        private final List<FakeMedia> children;
        private final String pk;
        private final boolean audio;

        FakeMedia(ImageInfo image, VideoInfo video, List<FakeMedia> children,
                String pk, boolean audio) {
            this.image = image;
            this.video = video;
            this.children = children;
            this.pk = pk;
            this.audio = audio;
        }

        public ImageInfo zz1() {
            return image;
        }

        public VideoInfo zz2() {
            return video;
        }

        public List<FakeMedia> zz3() {
            return children;
        }

        public String zz4() {
            return pk;
        }

        public boolean zz5() {
            return audio;
        }
    }

    private static void photoModel() {
        FakeMedia model = new FakeMedia(
                new ImageInfo("https://cdn.example/large.jpg", 1080), null,
                null, "3123456789", false);
        ObservedMedia media = MediaModelReader.read(model);
        check(media != null, "a photo model is read");
        check(DownloadKind.PHOTO == media.kind(), "the kind is a photo");
        check("https://cdn.example/large.jpg".equals(media.url()),
                "the image URL is read");
        check("3123456789".equals(media.id()), "the id is read");
        check(!media.hasAudio(), "a photo has no audio");
        check(media.isDownloadable(), "a photo with a URL is downloadable");
    }

    private static void videoModel() {
        FakeMedia model = new FakeMedia(
                new ImageInfo("https://cdn.example/cover.jpg", 640),
                new VideoInfo("https://cdn.example/high.mp4"), null, "987654321",
                true);
        ObservedMedia media = MediaModelReader.read(model);
        check(media != null, "a video model is read");
        check(DownloadKind.VIDEO == media.kind(), "the kind is a video");
        check("https://cdn.example/high.mp4".equals(media.url()),
                "the video URL wins over the cover image");
        check("video/mp4".equals(media.mimeType()), "the mime type is video");
        check(media.hasAudio(), "the audio flag is read");
    }

    private static void carouselModel() {
        FakeMedia first = new FakeMedia(
                new ImageInfo("https://cdn.example/a.jpg", 1080), null, null, "1",
                false);
        FakeMedia second = new FakeMedia(
                null, new VideoInfo("https://cdn.example/b.mp4"), null, "2", false);
        FakeMedia carousel = new FakeMedia(null, null,
                Arrays.asList(first, second), "55555", false);
        ObservedMedia media = MediaModelReader.read(carousel);
        check(media != null, "a carousel model is read");
        check(DownloadKind.CAROUSEL == media.kind(), "the kind is a carousel");
        check(!media.isDownloadable(), "a container is not downloadable as it is");
        List<ObservedMedia> children = media.children();
        check(children.size() == 2, "both children are read");
        check(DownloadKind.PHOTO == children.get(0).kind(), "the first child is a photo");
        check(DownloadKind.VIDEO == children.get(1).kind(), "the second child is a video");
        check("https://cdn.example/b.mp4".equals(children.get(1).url()),
                "the child's URL is read");
    }

    private static void modelRejection() {
        check(MediaModelReader.read(null) == null, "null is not media");
        check(MediaModelReader.read("a string") == null, "a string is not media");
        check(MediaModelReader.read(new Object()) == null,
                "an object without media is not media");
        FakeMedia empty = new FakeMedia(null, null, null, null, false);
        check(MediaModelReader.read(empty) == null,
                "a model without versions is not media");
        FakeMedia blankUrl = new FakeMedia(new ImageInfo("", 10), null, null, "1",
                false);
        check(MediaModelReader.read(blankUrl) == null,
                "a blank URL is not media");
        FakeMedia notALink = new FakeMedia(new ImageInfo("not-a-link", 10), null,
                null, "1", false);
        check(MediaModelReader.read(notALink) == null,
                "a value that is not a link is not a URL");
    }

    private static void observationToItem() {
        FakeMedia model = new FakeMedia(
                new ImageInfo("https://cdn.example/large.jpg", 1080), null, null,
                "3123456789", false);
        ObservedMedia media = MediaModelReader.read(model);
        MediaItem item = media.toMediaItem();
        check("3123456789".equals(item.id()), "the engine item keeps the id");
        check(item.kind() == DownloadKind.PHOTO, "the engine item keeps the kind");
        check("https://cdn.example/large.jpg".equals(item.url()),
                "the engine item keeps the URL");
        check("id:3123456789".equals(MediaNaming.duplicateKey(item)),
                "the duplicate key is the stable media id");
    }

    private static void registryBounds() {
        MediaRegistry registry = new MediaRegistry(3);
        for (int index = 1; index <= 5; index++) {
            registry.observe(ObservedMedia.single("id-" + index, DownloadKind.PHOTO,
                    "https://cdn.example/" + index + ".jpg", "image/jpeg", "alice",
                    100, 100, 0L, 0L, false));
        }
        check(registry.size() == 3, "the registry stays within its capacity");
        List<ObservedMedia> list = registry.list();
        check(list.size() == 3, "the list has the capacity");
        check("id-5".equals(list.get(0).id()), "the newest observation is first");
        check("id-3".equals(list.get(2).id()), "the oldest kept observation is last");
        check(registry.find("id-1") == null, "an evicted observation is gone");
        check(registry.find("id-5") != null, "the newest observation is found");

        // The same media again moves to the front instead of being duplicated.
        registry.observe(ObservedMedia.single("id-3", DownloadKind.PHOTO,
                "https://cdn.example/3.jpg", "image/jpeg", "alice", 100, 100, 0L, 0L,
                false));
        check(registry.size() == 3, "a repeated observation does not grow the registry");
        check("id-3".equals(registry.list().get(0).id()),
                "the repeated observation is the newest one");

        registry.clear();
        check(registry.size() == 0, "the registry can be cleared");

        try {
            new MediaRegistry(0);
            throw new AssertionError("a capacity of zero must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            registry.observe(null);
            throw new AssertionError("a null observation must be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    /** A photo model that also exposes a list of plain tags or ids. */
    public static final class TaggedMedia {
        private final ImageInfo image;
        private final List<String> tags;
        private final List<Long> ids;

        TaggedMedia(ImageInfo image, List<String> tags, List<Long> ids) {
            this.image = image;
            this.tags = tags;
            this.ids = ids;
        }

        public ImageInfo zz1() {
            return image;
        }

        public List<String> zz2() {
            return tags;
        }

        public List<Long> zz7() {
            return ids;
        }

        public String zz4() {
            return "777";
        }
    }

    /**
     * A post with a non-empty list that is not a carousel must still be read as
     * its own media. This is the regression for a real Instagram shape: an
     * ordinary photo with tagged accounts, coauthors or a hashtag list.
     */
    private static void nonMediaListFallback() {
        TaggedMedia tagged = new TaggedMedia(
                new ImageInfo("https://cdn.example/tagged.jpg", 1080),
                Arrays.asList("alice", "bob"), Arrays.asList(1L, 2L));
        ObservedMedia media = MediaModelReader.read(tagged);
        check(media != null, "a photo with a tag list is still read");
        check(DownloadKind.PHOTO == media.kind(), "the tag list is not a carousel");
        check("https://cdn.example/tagged.jpg".equals(media.url()),
                "the photo URL is read");

        // A list of child models that carry no media is not a carousel either.
        FakeMedia emptyChild = new FakeMedia(null, null, null, "child", false);
        FakeMedia parent = new FakeMedia(
                new ImageInfo("https://cdn.example/parent.jpg", 1080), null,
                Arrays.asList(emptyChild), "888", false);
        ObservedMedia fallback = MediaModelReader.read(parent);
        check(fallback != null, "a photo with an unreadable child list is still read");
        check(DownloadKind.PHOTO == fallback.kind(),
                "an unreadable child list does not turn a photo into a carousel");
        check("https://cdn.example/parent.jpg".equals(fallback.url()),
                "the parent photo URL is read");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Keeps the unused-import checker honest. */
    static List<Object> empty() {
        return new ArrayList<Object>();
    }
}
