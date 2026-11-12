package com.feurstagram.extension.downloader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a downloadable media source out of an Instagram media model.
 *
 * Instagram's media models are obfuscated classes, but a handful of their
 * accessors keep stable names. Every name this class looks for was verified to
 * exist in Instagram 454.0.0.54.77 (versionCode 386014712):
 *
 * <ul>
 *   <li>{@code getImageUrl()} — the still image (photo, video cover);</li>
 *   <li>{@code getMediaType()} — 1 image, 2 video, 8 carousel;</li>
 *   <li>{@code getHasAudio()} — whether a video carries an audio track;</li>
 *   <li>{@code getOriginalWidth()} / {@code getOriginalHeight()};</li>
 *   <li>{@code getPk()} — the media id, used for file names and duplicates;</li>
 *   <li>{@code getUser()} → {@code getUsername()} — the owner;</li>
 *   <li>{@code getVersions()} — the version list, whose entries expose
 *       {@code getUrl()} and {@code getType()}.</li>
 * </ul>
 *
 * The lookup never guesses: a name that is not in the allowlist is never
 * called, and a model that exposes none of them yields an empty offer with a
 * reason — the downloader says "not available" instead of inventing a URL.
 *
 * Pure Java on purpose: the rules are unit-tested on the JVM without an
 * Android device (scripts/ci/test_downloader_logic.sh).
 */
public final class MediaResolver {

    private MediaResolver() {}

    private static final String[] IMAGE_URL_METHODS = {"getImageUrl"};
    private static final String[] IMAGE_URL_FIELDS = {"image_url", "display_url", "imageUrl"};

    private static final String[] VIDEO_URL_METHODS = {"getVideoUrl", "videoUrl"};
    private static final String[] VIDEO_URL_FIELDS = {"video_url", "videoUrl"};

    private static final String[] VERSIONS_METHODS = {"getVersions"};
    private static final String[] VERSIONS_FIELDS = {"versions", "video_versions", "videoVersions"};

    private static final String[] CAROUSEL_METHODS = {"getCarouselMedia", "getCarouselMediaList"};
    private static final String[] CAROUSEL_FIELDS = {"carousel_media", "carouselMedia"};

    private static final String[] MEDIA_ID_METHODS = {"getPk", "getMediaId"};
    private static final String[] MEDIA_ID_FIELDS = {"pk", "id", "media_id"};

    private static final String[] USERNAME_METHODS = {"getUsername"};
    private static final String[] USER_METHODS = {"getUser", "getOwner"};

    /** Reads the media id, or null. */
    public static String mediaId(Object model) {
        Object value = call(model, MEDIA_ID_METHODS);
        if (value == null) value = readField(model, MEDIA_ID_FIELDS);
        String id = asString(value);
        if (id == null) return null;
        // A media id is numeric in Instagram's schema; anything else is a
        // different kind of identifier and is not used as one.
        return id.matches("\\d{1,24}") ? id : null;
    }

    /** Reads the still image URL, or null. */
    public static String imageUrl(Object model) {
        String url = asString(call(model, IMAGE_URL_METHODS));
        if (url == null) url = asString(readField(model, IMAGE_URL_FIELDS));
        return isUsableUrl(url) ? url : null;
    }

    /** Reads the video URL, or null. */
    public static String videoUrl(Object model) {
        String url = asString(call(model, VIDEO_URL_METHODS));
        if (url == null) url = asString(readField(model, VIDEO_URL_FIELDS));
        if (isUsableUrl(url)) return url;
        // Fall back to the version list: the video is the entry whose URL is an
        // mp4 (the photo entries are jpg/webp).
        for (Object version : versions(model)) {
            Object versionUrl = call(version, new String[]{"getUrl"});
            if (versionUrl == null) versionUrl = readField(version, new String[]{"url"});
            String candidate = asString(versionUrl);
            if (isUsableUrl(candidate) && candidate.toLowerCase(java.util.Locale.US).contains(".mp4")) {
                return candidate;
            }
        }
        return null;
    }

    /** The version list of a media model, empty when it exposes none. */
    public static List<Object> versions(Object model) {
        Object value = call(model, VERSIONS_METHODS);
        if (value == null) value = readField(model, VERSIONS_FIELDS);
        return asList(value);
    }

    /** The children of a carousel, empty when the model is not a carousel. */
    public static List<Object> carouselChildren(Object model) {
        Object value = call(model, CAROUSEL_METHODS);
        if (value == null) value = readField(model, CAROUSEL_FIELDS);
        return asList(value);
    }

    /** The media type (1 image, 2 video, 8 carousel), or 0 when unknown. */
    public static int mediaType(Object model) {
        Object value = call(model, new String[]{"getMediaType"});
        if (value == null) value = readField(model, new String[]{"media_type", "mediaType"});
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }

    /** Whether the video carries an audio track. */
    public static boolean hasAudio(Object model) {
        Object value = call(model, new String[]{"getHasAudio"});
        if (value == null) value = readField(model, new String[]{"has_audio", "hasAudio"});
        return value instanceof Boolean && (Boolean) value;
    }

    /** The owner of the media, or null. */
    public static String owner(Object model) {
        Object user = call(model, USER_METHODS);
        if (user == null) return null;
        String name = asString(call(user, USERNAME_METHODS));
        if (name == null) name = asString(readField(user, new String[]{"username"}));
        return name;
    }

    /** The original width of the media, or 0. */
    public static int width(Object model) {
        Object value = call(model, new String[]{"getOriginalWidth"});
        if (value == null) value = readField(model, new String[]{"original_width", "originalWidth"});
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /** The original height of the media, or 0. */
    public static int height(Object model) {
        Object value = call(model, new String[]{"getOriginalHeight"});
        if (value == null) value = readField(model, new String[]{"original_height", "originalHeight"});
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /** A CDN URL we are willing to fetch: https and not a placeholder. */
    public static boolean isUsableUrl(String url) {
        if (url == null) return false;
        String value = url.trim();
        if (!value.startsWith("https://")) return false;
        return value.length() > "https://".length() + 8;
    }

    /** Turns a list, an array or null into a list of entries. */
    private static List<Object> asList(Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null) return out;
        if (value instanceof Iterable) {
            for (Object entry : (Iterable<?>) value) {
                if (entry != null) out.add(entry);
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object entry = java.lang.reflect.Array.get(value, i);
                if (entry != null) out.add(entry);
            }
        }
        return out;
    }

    private static Object call(Object target, String[] names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                Object value = method.invoke(target);
                if (value != null) return value;
            } catch (Throwable ignored) {
                // Not present on this model: try the next name.
            }
        }
        return null;
    }

    private static Object readField(Object target, String[] names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Field field = target.getClass().getField(name);
                Object value = field.get(target);
                if (value != null) return value;
            } catch (Throwable ignored) {
                // Not present on this model: try the next name.
            }
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        }
        if (value instanceof Number) return String.valueOf(value);
        return null;
    }
}
