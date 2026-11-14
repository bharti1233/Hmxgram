package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * M9 tests: reading the Instagram 454 media shape with the type-based
 * reader, and keeping the observations in a bounded registry.
 *
 * <p>The fake models below have the real 454 shape, not the old one: an
 * {@code ImageInfo} exposes <em>lists of candidates</em> and no string URL at
 * all, a video has no video info object but a list of {@code VideoUrl}
 * objects whose link is a public field, and a carousel child is itself a
 * media model. The accessor names are deliberately meaningless, because the
 * reader must never depend on a name.
 */
public final class MediaObservationTest {

    private MediaObservationTest() {
    }

    /** Entry point for the plain-JDK test runner. */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every M9 test group and returns the number of failures. */
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
        failures += group("video list is not a carousel", new Runnable() {
            @Override
            public void run() {
                videoListIsNotCarousel();
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
        failures += group("observation session", new Runnable() {
            @Override
            public void run() {
                observationSession();
            }
        });
        failures += group("non-media list fallback", new Runnable() {
            @Override
            public void run() {
                nonMediaListFallback();
            }
        });
        if (failures == 0) {
            System.out.println("PASS 8 downloader-observation tests");
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

    /** One image candidate, like ExtendedImageUrl: a getter and a width. */
    public static final class ImageCandidate {
        private final String url;
        private final int width;

        ImageCandidate(String url, int width) {
            this.url = url;
            this.width = width;
        }

        public String getUrl() {
            return url;
        }

        public int getWidth() {
            return width;
        }
    }

    /**
     * The image info object of the real 454 model: lists of candidates and no
     * string URL getter.
     */
    public static final class ImageInfo {
        private final List<ImageCandidate> candidates;

        ImageInfo(List<ImageCandidate> candidates) {
            this.candidates = candidates;
        }

        public List<ImageCandidate> zz1() {
            return candidates;
        }

        public double zz2() {
            return 1.0d;
        }
    }

    /** One video version, like VideoUrlImpl: a public string field, no getter. */
    public static final class VideoUrl {
        public final String A06;
        public final int A00;
        public final int A01;

        VideoUrl(String url) {
            this(url, 0, 0);
        }

        VideoUrl(String url, int width, int height) {
            this.A06 = url;
            this.A00 = width;
            this.A01 = height;
        }
    }

    /** A fake media model with the 454 shape. */
    public static final class FakeMedia {
        private final ImageInfo image;
        private final List<VideoUrl> videos;
        private final List<FakeMedia> children;
        private final String pk;
        private final boolean audio;
        private final boolean sponsored;

        FakeMedia(ImageInfo image, List<VideoUrl> videos, List<FakeMedia> children,
                String pk, boolean audio, boolean sponsored) {
            this.image = image;
            this.videos = videos;
            this.children = children;
            this.pk = pk;
            this.audio = audio;
            this.sponsored = sponsored;
        }

        public ImageInfo zzA() {
            return image;
        }

        public List<VideoUrl> zzB() {
            return videos;
        }

        public List<FakeMedia> zzC() {
            return children;
        }

        /** The real 454 accessor: the media id is read through {@code getId}. */
        public String getId() {
            return pk;
        }

        public String zzD() {
            return pk;
        }

        public boolean hasAudio() {
            return audio;
        }

        public boolean zzSponsored() {
            return sponsored;
        }
    }

    private static void photoModel() {
        FakeMedia model = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/small.jpg", 720),
                        new ImageCandidate("https://cdn.example/large.jpg", 1080))),
                null, null, "3123456789", false, false);
        ObservedMedia media = MediaModelReader.read(model);
        check(media != null, "a photo model is read");
        check(DownloadKind.PHOTO == media.kind(), "the kind is a photo");
        check("https://cdn.example/large.jpg".equals(media.url()),
                "the widest candidate is read");
        check("3123456789".equals(media.id()), "the numeric id is read");
        check(!media.hasAudio(), "a photo has no audio");
        check(media.isDownloadable(), "a photo with a URL is downloadable");

        // The real 454 model exposes getId(); it must win over another numeric
        // string accessor, which would otherwise key the media wrongly.
        IdPriorityMedia priority = new IdPriorityMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/p.jpg", 1080))),
                "1234567890", "9999999999");
        ObservedMedia priorityMedia = MediaModelReader.read(priority);
        check(priorityMedia != null, "a model with a named id is read");
        check("1234567890".equals(priorityMedia.id()),
                "the named id wins over another numeric string");
    }

    private static void videoModel() {
        FakeMedia model = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/cover.jpg", 640))),
                Arrays.asList(
                        new VideoUrl("https://cdn.example/low.mp4", 640, 360),
                        new VideoUrl("https://cdn.example/high.mp4", 1080, 1920),
                        new VideoUrl("https://cdn.example/medium.mp4", 720, 1280)),
                null, "987654321", true, false);
        // The hook hands the versions with the model: the paired read.
        ObservedMedia paired = MediaModelReader.read(model, model.zzB());
        check(paired != null, "a paired video model is read");
        check(DownloadKind.VIDEO == paired.kind(), "the kind is a video");
        check("https://cdn.example/high.mp4".equals(paired.url()),
                "the highest playable rendition wins, not the first one");
        check("video/mp4".equals(paired.mimeType()), "the mime type is video");
        check(paired.hasAudio(), "the audio flag is read");
        check(paired.id().equals("987654321"), "the video keeps its media id");

        // Without the handed list, the reader still finds the model's own
        // video versions; this is how a carousel child is read.
        ObservedMedia found = MediaModelReader.read(model);
        check(found != null, "the video model is read on its own");
        check(DownloadKind.VIDEO == found.kind(), "the own video list is found");
        check("https://cdn.example/high.mp4".equals(found.url()),
                "the own video list uses the highest rendition too");

        // An unrelated true boolean is not audio.
        FakeMedia loud = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/cover.jpg", 640))),
                Arrays.asList(new VideoUrl("https://cdn.example/high.mp4", 1080, 1920)),
                null, "987654321", false, true);
        ObservedMedia noAudio = MediaModelReader.read(loud);
        check(noAudio != null && !noAudio.hasAudio(),
                "an unrelated boolean does not claim audio");
    }

    private static void carouselModel() {
        FakeMedia first = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/a.jpg", 1080))),
                null, null, "1234567890123", false, false);
        FakeMedia second = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/b-cover.jpg", 1080))),
                Arrays.asList(new VideoUrl("https://cdn.example/b.mp4")),
                null, "1234567890124", false, false);
        FakeMedia carousel = new FakeMedia(null, null,
                Arrays.asList(first, second), "1234567890125", false, false);
        ObservedMedia media = MediaModelReader.read(carousel);
        check(media != null, "a carousel model is read");
        check(DownloadKind.CAROUSEL == media.kind(), "the kind is a carousel");
        check(!media.isDownloadable(), "a container is not downloadable as it is");
        List<ObservedMedia> children = media.children();
        check(children.size() == 2, "both children are read");
        check(DownloadKind.PHOTO == children.get(0).kind(), "the first child is a photo");
        check(DownloadKind.VIDEO == children.get(1).kind(),
                "the video child is read as a video");
        check("https://cdn.example/b.mp4".equals(children.get(1).url()),
                "the child's video URL is read");
    }

    /**
     * The regression that hid the chip on every surface: the old reader saw
     * the image candidates (or the video versions) as a carousel and turned a
     * plain photo or video into a container, so nothing was downloadable.
     */
    private static void videoListIsNotCarousel() {
        FakeMedia photo = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/only.jpg", 1080))),
                null, null, "1234567890", false, false);
        ObservedMedia photoMedia = MediaModelReader.read(photo);
        check(photoMedia != null && photoMedia.kind() == DownloadKind.PHOTO,
                "a photo with candidate lists is a photo, not a carousel");

        FakeMedia video = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/cover.jpg", 640))),
                Arrays.asList(new VideoUrl("https://cdn.example/clip.mp4")),
                null, "1234567890", false, false);
        ObservedMedia videoMedia = MediaModelReader.read(video);
        check(videoMedia != null && videoMedia.kind() == DownloadKind.VIDEO,
                "a video with a versions list is a video, not a carousel");
    }

    private static void modelRejection() {
        check(MediaModelReader.read(null) == null, "null is not media");
        check(MediaModelReader.read("a string") == null, "a string is not media");
        check(MediaModelReader.read(new Object()) == null,
                "an object without media is not media");
        FakeMedia empty = new FakeMedia(null, null, null, null, false, false);
        check(MediaModelReader.read(empty) == null,
                "a model without versions is not media");
        FakeMedia blankUrl = new FakeMedia(
                new ImageInfo(Arrays.asList(new ImageCandidate("", 10))), null,
                null, "1234567890", false, false);
        check(MediaModelReader.read(blankUrl) == null,
                "a blank URL is not media");
        FakeMedia notALink = new FakeMedia(
                new ImageInfo(Arrays.asList(new ImageCandidate("not-a-link", 10))),
                null, null, "1234567890", false, false);
        check(MediaModelReader.read(notALink) == null,
                "a value that is not a link is not a URL");

        // A model whose only string is not numeric has no id. Taking the
        // first string would key every media under the same value and break
        // duplicate detection.
        FakeMedia named = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/named.jpg", 1080))),
                null, null, "photo", false, false);
        ObservedMedia namedMedia = MediaModelReader.read(named);
        check(namedMedia != null, "a model with a non-numeric string is still media");
        check(namedMedia.id() == null, "a non-numeric string is not used as an id");
    }

    private static void observationToItem() {
        FakeMedia model = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/large.jpg", 1080))),
                null, null, "3123456789", false, false);
        ObservedMedia media = MediaModelReader.read(model);
        MediaItem item = media.toMediaItem();
        check("3123456789".equals(item.id()), "the engine item keeps the id");
        check(item.kind() == DownloadKind.PHOTO, "the engine item keeps the kind");
        check("https://cdn.example/large.jpg".equals(item.url()),
                "the engine item keeps the URL");
        check("id:3123456789".equals(MediaNaming.duplicateKey(item)),
                "the duplicate key is the stable media id");
    }

    private static void observationSession() {
        ObservationSession session = new ObservationSession();
        long now = 1000L;
        ObservedMedia first = ObservedMedia.single("r1", DownloadKind.VIDEO,
                "https://cdn.example/r1.mp4?token=a", "video/mp4", "alice",
                1080, 1920, 0L, 0L, true);
        check(session.observe(first, now) == ObservationSession.Outcome.ACCEPTED,
                "the first observation becomes current");
        check(session.latest() == first, "the latest observation is kept");
        check(session.latestAgeMs(now + 500L) == 500L, "the age is measured");
        check(session.acceptedCount() == 1L, "one media was accepted");

        // The same Reel observed again: segments, cover and the model itself
        // are one media, never several entries.
        ObservedMedia again = ObservedMedia.single("r1", DownloadKind.VIDEO,
                "https://cdn.example/r1.mp4?token=b", "video/mp4", "alice",
                1080, 1920, 0L, 0L, true);
        check(session.observe(again, now + 1000L)
                        == ObservationSession.Outcome.REFRESHED,
                "a repeated observation refreshes instead of duplicating");
        check(session.latest() == again, "the refreshed snapshot is current");
        check(session.acceptedCount() == 1L,
                "a repeated observation does not increase the accepted count");
        check(session.refreshCount() == 1L, "the refresh is counted");

        ObservedMedia other = ObservedMedia.single("r2", DownloadKind.VIDEO,
                "https://cdn.example/r2.mp4", "video/mp4", "bob",
                720, 1280, 0L, 0L, true);
        check(session.observe(other, now + 2000L)
                        == ObservationSession.Outcome.ACCEPTED,
                "a new media becomes current");
        check(session.acceptedCount() == 2L, "the new media is counted");

        check(session.observe(null, now + 3000L)
                        == ObservationSession.Outcome.IGNORED,
                "a null observation is ignored");
        check(session.latest() == other, "a null observation changes nothing");

        // Identity without an id is the URL without its query: a changing CDN
        // signature must not turn one file into many.
        ObservedMedia signedA = ObservedMedia.single(null, DownloadKind.PHOTO,
                "https://cdn.example/a.jpg?sig=1", "image/jpeg", "alice",
                100, 100, 0L, 0L, false);
        ObservedMedia signedB = ObservedMedia.single(null, DownloadKind.PHOTO,
                "https://cdn.example/a.jpg?sig=2", "image/jpeg", "alice",
                100, 100, 0L, 0L, false);
        check(signedA.identity().equals(signedB.identity()),
                "the query string is not part of the media identity");

        session.clear();
        check(!session.hasObservation(), "the session can be cleared");
        check(session.latestAgeMs(now) == Long.MAX_VALUE,
                "a cleared session has no age");
    }

    /** A photo model that exposes the named id and another numeric string. */
    public static final class IdPriorityMedia {
        private final ImageInfo image;
        private final String id;
        private final String decoy;

        IdPriorityMedia(ImageInfo image, String id, String decoy) {
            this.image = image;
            this.id = id;
            this.decoy = decoy;
        }

        public ImageInfo zz1() {
            return image;
        }

        public String getId() {
            return id;
        }

        public String zzZ() {
            return decoy;
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
            return "1234567890";
        }
    }

    /**
     * A post with a non-empty list that is not a carousel must still be read as
     * its own media. This is the regression for a real Instagram shape: an
     * ordinary photo with tagged accounts, coauthors or a hashtag list.
     */
    private static void nonMediaListFallback() {
        TaggedMedia tagged = new TaggedMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/tagged.jpg", 1080))),
                Arrays.asList("alice", "bob"), Arrays.asList(1L, 2L));
        ObservedMedia media = MediaModelReader.read(tagged);
        check(media != null, "a photo with a tag list is still read");
        check(DownloadKind.PHOTO == media.kind(), "the tag list is not a carousel");
        check("https://cdn.example/tagged.jpg".equals(media.url()),
                "the photo URL is read");

        // A list of child models that carry no media is not a carousel either.
        FakeMedia emptyChild = new FakeMedia(null, null, null, "1234567890123",
                false, false);
        FakeMedia parent = new FakeMedia(
                new ImageInfo(Arrays.asList(
                        new ImageCandidate("https://cdn.example/parent.jpg", 1080))),
                null, Arrays.asList(emptyChild), "1234567890124", false, false);
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
