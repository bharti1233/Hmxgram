import com.feurstagram.extension.downloader.DownloadError;
import com.feurstagram.extension.downloader.DownloadTask;
import com.feurstagram.extension.downloader.DuplicateDetector;
import com.feurstagram.extension.downloader.FeedMediaAdapter;
import com.feurstagram.extension.downloader.MediaItem;
import com.feurstagram.extension.downloader.MediaKind;
import com.feurstagram.extension.downloader.MediaNaming;
import com.feurstagram.extension.downloader.MediaResolver;
import com.feurstagram.extension.downloader.ReelMediaAdapter;
import com.feurstagram.extension.downloader.StoryMediaAdapter;

import java.util.List;

/**
 * Offline tests for the downloader's resolution and bookkeeping rules.
 *
 * Run by scripts/ci/test_downloader_logic.sh with plain javac: no Android
 * device and no emulator is involved.
 */
public final class DownloaderLogicTest {

    private static int checks = 0;

    public static void main(String[] args) {
        photoPost();
        videoPost();
        videoWithoutAudio();
        carouselPost();
        postWithoutUrl();
        duplicates();
        naming();
        taskLifecycle();
        errorTaxonomy();
        expiredStory();
        liveStory();
        reel();

        System.out.println("Downloader logic tests passed (" + checks + " checks)");
    }

    // --- Instagram-shaped fake models -------------------------------------

    public static class FakeUser {
        private final String username;

        FakeUser(String username) {
            this.username = username;
        }

        public String getUsername() {
            return username;
        }
    }

    public static class FakeVersion {
        private final String url;
        private final int type;

        FakeVersion(String url, int type) {
            this.url = url;
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public int getType() {
            return type;
        }
    }

    public static class FakeMedia {
        String id = "3123456789012345678";
        String imageUrl;
        String videoUrl;
        int mediaType = 1;
        boolean hasAudio;
        int width = 1080;
        int height = 1920;
        String username = "hmx_insta";
        FakeMedia[] carousel;
        Long expiringAt;

        public String getPk() {
            return id;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public Integer getMediaType() {
            return mediaType;
        }

        public boolean getHasAudio() {
            return hasAudio;
        }

        public int getOriginalWidth() {
            return width;
        }

        public int getOriginalHeight() {
            return height;
        }

        public FakeUser getUser() {
            return new FakeUser(username);
        }

        public FakeMedia[] getCarouselMedia() {
            return carousel;
        }

        public List<FakeVersion> getVersions() {
            if (videoUrl == null) return null;
            return java.util.Arrays.asList(
                    new FakeVersion("https://cdn.example/photo.jpg", 1),
                    new FakeVersion(videoUrl, 2));
        }

        public Long getExpiringAt() {
            return expiringAt;
        }
    }

    // --- tests -------------------------------------------------------------

    private static void photoPost() {
        FakeMedia media = new FakeMedia();
        media.imageUrl = "https://cdn.example/photo.jpg";
        MediaItem.Offer offer = new FeedMediaAdapter().resolve(media);
        check("photo: one item", offer.items().size() == 1);
        MediaItem item = offer.items().get(0);
        check("photo: variant", item.variant() == MediaItem.Variant.PHOTO);
        check("photo: mime", "image/jpeg".equals(item.mimeType()));
        check("photo: extension", "jpg".equals(item.extension()));
        check("photo: id", "3123456789012345678".equals(item.mediaId()));
        check("photo: owner", "hmx_insta".equals(MediaResolver.owner(media)));
    }

    private static void videoPost() {
        FakeMedia media = new FakeMedia();
        media.mediaType = 2;
        media.hasAudio = true;
        media.videoUrl = "https://cdn.example/video.mp4";
        media.imageUrl = "https://cdn.example/cover.jpg";
        MediaItem.Offer offer = new FeedMediaAdapter().resolve(media);
        check("video: three items", offer.items().size() == 3);
        boolean video = false, audio = false, cover = false;
        for (MediaItem item : offer.items()) {
            if (item.variant() == MediaItem.Variant.VIDEO) video = true;
            if (item.variant() == MediaItem.Variant.AUDIO) audio = true;
            if (item.variant() == MediaItem.Variant.COVER) cover = true;
        }
        check("video: video offered", video);
        check("video: audio offered", audio);
        check("video: cover offered", cover);
        for (MediaItem item : offer.items()) {
            if (item.variant() == MediaItem.Variant.VIDEO) {
                check("video: mime", "video/mp4".equals(item.mimeType()));
                check("video: extension", "mp4".equals(item.extension()));
            }
            if (item.variant() == MediaItem.Variant.AUDIO) {
                check("audio: mime", "audio/mpeg".equals(item.mimeType()));
                check("audio: extension", "mp3".equals(item.extension()));
            }
        }
    }

    private static void videoWithoutAudio() {
        FakeMedia media = new FakeMedia();
        media.mediaType = 2;
        media.hasAudio = false;
        media.videoUrl = "https://cdn.example/video.mp4";
        MediaItem.Offer offer = new FeedMediaAdapter().resolve(media);
        boolean audio = false;
        for (MediaItem item : offer.items()) {
            if (item.variant() == MediaItem.Variant.AUDIO) audio = true;
        }
        check("video without audio: no audio item", !audio);
    }

    private static void carouselPost() {
        FakeMedia first = new FakeMedia();
        first.id = "111";
        first.imageUrl = "https://cdn.example/one.jpg";
        FakeMedia second = new FakeMedia();
        second.id = "222";
        second.imageUrl = "https://cdn.example/two.jpg";
        FakeMedia carousel = new FakeMedia();
        carousel.id = "999";
        carousel.mediaType = 8;
        carousel.carousel = new FakeMedia[]{first, second};
        MediaItem.Offer offer = new FeedMediaAdapter().resolve(carousel);
        check("carousel: two children", offer.items().size() == 2);
    }

    private static void postWithoutUrl() {
        FakeMedia media = new FakeMedia();
        MediaItem.Offer offer = new FeedMediaAdapter().resolve(media);
        check("no url: nothing offered", offer.isEmpty());
        check("no url: reason given", !offer.unavailable().isEmpty());
        for (MediaItem item : offer.items()) {
            check("no url: never a fabricated link", item.url() == null || item.url().length() > 0);
        }
    }

    private static void duplicates() {
        DuplicateDetector detector = new DuplicateDetector();
        check("duplicate: first is new", !detector.isDuplicate("123", "https://cdn.example/a.jpg"));
        detector.markDownloaded("123", "https://cdn.example/a.jpg");
        check("duplicate: second is known", detector.isDuplicate("123", "https://cdn.example/b.jpg"));
        check("duplicate: other is new", !detector.isDuplicate("456", "https://cdn.example/b.jpg"));
        detector.markDownloaded(null, "https://cdn.example/c.jpg");
        check("duplicate: url fallback", detector.isDuplicate(null, "https://cdn.example/c.jpg"));
        check("duplicate: url ignores query",
                detector.isDuplicate(null, "https://cdn.example/c.jpg?oh=1"));
    }

    private static void naming() {
        check("naming: strips separators", !MediaNaming.fileName("a/b:c", ".jpg").contains("/"));
        check("naming: keeps extension",
                MediaNaming.fileName("name", ".jpg").equals("name.jpg"));
        check("naming: no double extension",
                MediaNaming.fileName("name.jpg", "jpg").equals("name.jpg"));
        check("naming: caps length",
                MediaNaming.fileName(repeat("x", 300), ".jpg").length() <= MediaNaming.MAX_NAME_LENGTH);
        check("naming: empty falls back", MediaNaming.fileName("", ".jpg").startsWith("hmx-insta"));
    }

    private static void taskLifecycle() {
        FakeMedia media = new FakeMedia();
        media.imageUrl = "https://cdn.example/photo.jpg";
        MediaItem item = new FeedMediaAdapter().resolve(media).items().get(0);
        DownloadTask task = new DownloadTask(item, "hmx_insta", "Download Photo");
        check("task: starts queued", task.state() == DownloadTask.State.QUEUED);
        task.start(1000);
        check("task: running", task.state() == DownloadTask.State.RUNNING);
        task.progress(500, 1000);
        check("task: progress", task.progress() == 50);
        task.succeed("/storage/emulated/0/Pictures/HMXInsta/x.jpg");
        check("task: done", task.state() == DownloadTask.State.DONE);
        check("task: path kept", task.savedPath() != null);

        DownloadTask failed = new DownloadTask(item, "hmx_insta", "Download Photo");
        failed.start(0);
        failed.fail(DownloadError.NETWORK);
        check("task: failed", failed.state() == DownloadTask.State.FAILED);
        check("task: network is recoverable", DownloadError.isRecoverable(DownloadError.NETWORK));
        check("task: expired is not recoverable", !DownloadError.isRecoverable(DownloadError.EXPIRED));
        check("task: retry allowed", failed.retry());
        failed.fail(DownloadError.EXPIRED);
        check("task: retry refused for expired", !failed.retry());

        DownloadTask cancelled = new DownloadTask(item, "hmx_insta", "Download Photo");
        cancelled.start(0);
        cancelled.cancel();
        check("task: cancelled", cancelled.state() == DownloadTask.State.CANCELLED);
    }

    private static void errorTaxonomy() {
        String[] errors = {DownloadError.NETWORK, DownloadError.EXPIRED, DownloadError.STORAGE,
                DownloadError.CANCELLED, DownloadError.UNSUPPORTED, DownloadError.DUPLICATE,
                DownloadError.SERVER};
        for (String error : errors) {
            check("error message: " + error, DownloadError.message(error) != null
                    && !DownloadError.message(error).isEmpty());
        }
    }

    private static void expiredStory() {
        FakeMedia media = new FakeMedia();
        media.imageUrl = "https://cdn.example/story.jpg";
        media.expiringAt = System.currentTimeMillis() - 60_000L;
        MediaItem.Offer offer = new StoryMediaAdapter().resolve(media);
        check("expired story: nothing offered", offer.isEmpty());
        String reason = offer.unavailable().get(MediaItem.Variant.PHOTO);
        check("expired story: reason mentions expiry", reason != null && reason.contains("expired"));
    }

    private static void liveStory() {
        FakeMedia media = new FakeMedia();
        media.imageUrl = "https://cdn.example/story.jpg";
        media.expiringAt = System.currentTimeMillis() + 3_600_000L;
        MediaItem.Offer offer = new StoryMediaAdapter().resolve(media);
        check("live story: photo offered", offer.items().size() == 1);
    }

    private static void reel() {
        FakeMedia media = new FakeMedia();
        media.mediaType = 2;
        media.hasAudio = true;
        media.videoUrl = "https://cdn.example/reel.mp4";
        media.imageUrl = "https://cdn.example/reel-cover.jpg";
        MediaItem.Offer offer = new ReelMediaAdapter().resolve(media);
        boolean video = false, audio = false, cover = false;
        for (MediaItem item : offer.items()) {
            if (item.variant() == MediaItem.Variant.VIDEO) video = true;
            if (item.variant() == MediaItem.Variant.AUDIO) audio = true;
            if (item.variant() == MediaItem.Variant.COVER) cover = true;
        }
        check("reel: video", video);
        check("reel: audio", audio);
        check("reel: cover", cover);
    }

    private static String repeat(String value, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(value);
        return out.toString();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("FAILED: " + what);
    }
}
