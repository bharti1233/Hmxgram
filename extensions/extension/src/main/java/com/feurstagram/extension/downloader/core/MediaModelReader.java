package com.feurstagram.extension.downloader.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a media model into an {@link ObservedMedia}.
 *
 * <p>Instagram's media model is map-backed and its accessors are renamed in
 * every release, so the reader never looks for an accessor by name. It looks
 * for them by <em>type</em> and by the <em>shape</em> of what they expose:
 *
 * <ol>
 *   <li>the image accessor is the no-argument method whose result type is
 *       called {@code ImageInfo};</li>
 *   <li>an image info object does not expose its URL as a string in the
 *       current Instagram releases: it exposes <em>lists</em> of candidates,
 *       and the candidates expose {@code getUrl()}. The reader descends into
 *       those lists, into the additional candidates and into the public
 *       string fields of a candidate;</li>
 *   <li>the video versions are a list of {@code VideoUrl}/{@code
 *       VideoVersion} objects. Instagram hands that list to the observer
 *       through the {@code video_versions} accessor; the reader also looks
 *       for such a list on a model when it was not handed one, so a carousel
 *       child is not mistaken for a still image;</li>
 *   <li>the children of a carousel are a list whose entries are models again
 *       (each one exposes its own image accessor). A list of ids, tags or
 *       media candidates is not a carousel.</li>
 * </ol>
 *
 * <p>Every step is validated by the value it produces, so a renamed or
 * reshaped model yields nothing instead of a wrong URL. Nothing here throws:
 * a media that cannot be read is simply not observed.
 *
 * <p>The reader holds no Instagram class, no session and no credential: it
 * only reads the object it is given.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class MediaModelReader {

    /** The simple type name of the image info object. */
    public static final String IMAGE_INFO = "ImageInfo";

    /** How deep the candidate descent may go. */
    private static final int MAX_DESCENT = 3;

    /** How many candidates are kept; a real media exposes far fewer. */
    private static final int MAX_CANDIDATES = 64;

    private MediaModelReader() {
    }

    /**
     * Reads a media model.
     *
     * @param model the model Instagram handed to its row binder
     * @return what it describes, or {@code null} when it cannot be read
     */
    public static ObservedMedia read(Object model) {
        return read(model, null);
    }

    /**
     * Reads a media model, with the video versions Instagram already read for
     * it.
     *
     * <p>Instagram has no separate video info object in the current releases:
     * the video URLs live in a list the media accessor returns. The
     * {@code video_versions} hook hands that list here, so a video is read as
     * a video instead of falling back to its cover image. A {@code null} list
     * is allowed: the reader then tries to find such a list on the model.
     *
     * @param model   the model Instagram handed to its row binder
     * @param videos  the video versions list, or {@code null}
     * @return what it describes, or {@code null} when it cannot be read
     */
    public static ObservedMedia read(Object model, Object videos) {
        if (model == null) {
            return null;
        }
        try {
            return readModel(model, 0, videos);
        } catch (RuntimeException e) {
            // A model that cannot be read is not an error.
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    private static ObservedMedia readModel(Object model, int depth, Object videos) {
        if (model == null || depth > 3) {
            return null;
        }
        List<Object> children = childrenOf(model);
        if (!children.isEmpty()) {
            List<ObservedMedia> observed = new ArrayList<ObservedMedia>();
            for (Object child : children) {
                ObservedMedia media = readModel(child, depth + 1, null);
                if (media != null && media.isDownloadable()) {
                    observed.add(media);
                }
            }
            if (!observed.isEmpty()) {
                return ObservedMedia.carousel(idOf(model), observed, ownerOf(model), 0L);
            }
            // The list was not a carousel (for example a list of tagged users
            // on an ordinary photo). Falling through instead of rejecting the
            // model is what keeps the save action available on those posts.
        }

        String imageUrl = urlOf(infoOf(model, IMAGE_INFO));
        String videoUrl = videoUrlOf(model, videos);
        String url;
        DownloadKind kind;
        if (videoUrl != null) {
            url = videoUrl;
            kind = DownloadKind.VIDEO;
        } else if (imageUrl != null) {
            url = imageUrl;
            kind = DownloadKind.PHOTO;
        } else {
            return null;
        }
        boolean hasAudio = hasAudio(model);
        return ObservedMedia.single(idOf(model), kind, url,
                kind == DownloadKind.VIDEO ? "video/mp4" : "image/jpeg",
                ownerOf(model), 0, 0, 0L, 0L, hasAudio);
    }

    /** The info object of the given kind, or {@code null}. */
    private static Object infoOf(Object model, String simpleTypeName) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (!simpleTypeName.equals(simpleName(method.getReturnType().getName()))) {
                continue;
            }
            try {
                Object value = method.invoke(model);
                if (value != null) {
                    return value;
                }
            } catch (Exception e) {
                // Try the next accessor.
            } catch (LinkageError e) {
                // Try the next accessor.
            }
        }
        return null;
    }

    /**
     * The URL an info object exposes, by descending into its candidate lists.
     *
     * <p>The current {@code ImageInfo} has no {@code getUrl()}: it returns
     * lists of candidates, and {@code AdditionalCandidates} in turn returns
     * candidates. Each candidate exposes {@code getUrl()} and
     * {@code getWidth()}. A {@code VideoUrl}/{@code VideoVersion} exposes its
     * URL as a getter or as a public field. The highest width wins, which
     * does not depend on the order Instagram happened to store the candidates.
     */
    private static String urlOf(Object source) {
        List<UrlCandidate> candidates = new ArrayList<UrlCandidate>();
        collectUrls(source, 0, candidates);
        return bestUrl(candidates);
    }

    private static void collectUrls(Object source, int depth,
            List<UrlCandidate> candidates) {
        if (source == null || depth > MAX_DESCENT || candidates.size() >= MAX_CANDIDATES) {
            return;
        }
        if (source instanceof CharSequence) {
            addCandidate(source.toString(), 0, candidates, false);
            return;
        }
        if (source instanceof List) {
            for (Object entry : (List<?>) source) {
                collectUrls(entry, depth + 1, candidates);
            }
            return;
        }

        int width = widthOf(source);

        // A video URL object in the current releases carries its link in a
        // public field, not in a getter. Public fields are the one place the
        // reader accepts without a getter, and they are the weakest evidence:
        // an object can hold other http strings than its media URL. Only an
        // http string is used, and a getter still wins over a field.
        for (Field field : source.getClass().getFields()) {
            if (field.getType() != String.class) {
                continue;
            }
            try {
                addCandidate((String) field.get(source), width, candidates, false);
            } catch (Exception e) {
                // Try the next field.
            } catch (LinkageError e) {
                // Try the next field.
            }
        }

        for (Method method : source.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            try {
                if (returned == String.class) {
                    addCandidate((String) method.invoke(source), width, candidates, true);
                } else if (List.class.isAssignableFrom(returned)
                        || isAdditionalCandidates(returned)
                        || isUrlObject(returned)) {
                    collectUrls(method.invoke(source), depth + 1, candidates);
                }
            } catch (Exception e) {
                // Try the next accessor.
            } catch (LinkageError e) {
                // Try the next accessor.
            }
        }
    }

    private static void addCandidate(String value, int width,
            List<UrlCandidate> candidates, boolean fromGetter) {
        if (value == null) {
            return;
        }
        String text = value.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            return;
        }
        if (text.length() < 8) {
            return;
        }
        candidates.add(new UrlCandidate(text, width, fromGetter));
    }

    /**
     * The best candidate.
     *
     * <p>A getter beats a public field, so an object's own {@code getUrl()}
     * wins over an unrelated http string the object also exposes. Among
     * getters the highest width wins, which does not depend on the order
     * Instagram happened to store the candidates.
     */
    private static String bestUrl(List<UrlCandidate> candidates) {
        UrlCandidate best = null;
        for (UrlCandidate candidate : candidates) {
            if (best == null
                    || candidate.preferred && !best.preferred
                    || candidate.preferred == best.preferred && candidate.width > best.width) {
                best = candidate;
            }
        }
        return best == null ? null : best.url;
    }

    private static int widthOf(Object source) {
        for (Method method : source.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            if (returned != int.class && returned != Integer.class) {
                continue;
            }
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("width")) {
                continue;
            }
            try {
                Object value = method.invoke(source);
                if (value instanceof Integer && (Integer) value > 0) {
                    return (Integer) value;
                }
            } catch (Exception e) {
                // Try the next accessor.
            } catch (LinkageError e) {
                // Try the next accessor.
            }
        }
        return 0;
    }

    /** Whether the type is the additional candidates holder of an image info. */
    private static boolean isAdditionalCandidates(Class<?> type) {
        return "AdditionalCandidates".equals(simpleName(type.getName()));
    }

    /**
     * Whether the type is a URL object rather than a media model.
     *
     * <p>An additional-candidates holder returns its candidates through
     * getters of such a type, so the reader has to follow them; a media model
     * is never one of these.
     */
    private static boolean isUrlObject(Class<?> type) {
        String name = simpleName(type.getName());
        return name.contains("ImageUrl") || name.contains("VideoUrl")
                || name.contains("VideoVersion");
    }

    /**
     * The video URL of a media.
     *
     * @param videos the list the {@code video_versions} accessor returned, or
     *               {@code null}
     */
    private static String videoUrlOf(Object model, Object videos) {
        String provided = urlOf(videos);
        if (provided != null) {
            return provided;
        }
        // A carousel child is read without a list handed to the observer: it
        // still exposes its own video versions. A list is a video list only
        // when every entry looks like a video version, so a list of image
        // candidates or of models cannot be mistaken for one.
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            if (!List.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Object value;
            try {
                value = method.invoke(model);
            } catch (Exception e) {
                continue;
            } catch (LinkageError e) {
                continue;
            }
            if (!(value instanceof List)) {
                continue;
            }
            List<?> entries = (List<?>) value;
            if (isVideoVersionList(entries)) {
                String url = urlOf(entries);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    /** Whether every entry of a list looks like a video version object. */
    private static boolean isVideoVersionList(List<?> entries) {
        if (entries.isEmpty()) {
            return false;
        }
        int seen = 0;
        for (Object entry : entries) {
            if (entry == null) {
                continue;
            }
            seen++;
            String type = simpleName(entry.getClass().getName());
            if (!type.contains("VideoUrl") && !type.contains("VideoVersion")) {
                return false;
            }
        }
        return seen > 0;
    }

    /** The child models of a carousel, or an empty list. */
    private static List<Object> childrenOf(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            if (!List.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Object value;
            try {
                value = method.invoke(model);
            } catch (Exception e) {
                continue;
            } catch (LinkageError e) {
                continue;
            }
            if (!(value instanceof List)) {
                continue;
            }
            List<?> entries = (List<?>) value;
            if (entries.isEmpty()) {
                continue;
            }
            List<Object> models = new ArrayList<Object>();
            for (Object entry : entries) {
                // A carousel child is itself a media model: it exposes the
                // image accessor the reader looks for. A list of ids, tags or
                // media candidates (whose entries are candidates, not models)
                // is therefore not a carousel, and the reader falls through
                // to the model's own image instead of hiding the save action.
                if (entry != null && entry != model && hasImageInfoAccessor(entry)) {
                    models.add(entry);
                }
            }
            if (!models.isEmpty()) {
                return models;
            }
        }
        return new ArrayList<Object>();
    }

    /** Whether an object exposes the image accessor, so it is media-like. */
    private static boolean hasImageInfoAccessor(Object entry) {
        for (Method method : entry.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            if (IMAGE_INFO.equals(simpleName(method.getReturnType().getName()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stable id of the model, when it exposes one.
     *
     * <p>Instagram media ids are numeric. A model exposes many string getters
     * that are not the id, so a non-numeric value is refused: taking the first
     * string would key every post under the same unrelated value and break
     * the downloader's duplicate detection.
     */
    private static String idOf(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            if (returned != String.class && returned != Long.class
                    && returned != Integer.class && returned != long.class
                    && returned != int.class) {
                continue;
            }
            try {
                Object value = method.invoke(model);
                if (value == null) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                if (isNumericIdentifier(text)) {
                    return text;
                }
            } catch (Exception e) {
                // Try the next accessor.
            } catch (LinkageError e) {
                // Try the next accessor.
            }
        }
        return null;
    }

    private static boolean isNumericIdentifier(String text) {
        if (text == null || text.length() < 5 || text.length() > 40) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** The owner of the media, when the model exposes one. */
    private static String ownerOf(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            String type = simpleName(method.getReturnType().getName());
            if (!"User".equals(type) && !"ReelUser".equals(type)
                    && !"UserModel".equals(type)) {
                continue;
            }
            try {
                Object user = method.invoke(model);
                if (user == null) {
                    continue;
                }
                String fallback = null;
                for (Method getter : user.getClass().getMethods()) {
                    if (getter.getParameterTypes().length != 0 || isObjectMethod(getter)) {
                        continue;
                    }
                    if (!String.class.equals(getter.getReturnType())) {
                        continue;
                    }
                    Object value = getter.invoke(user);
                    if (!(value instanceof String)) {
                        continue;
                    }
                    String text = ((String) value).trim();
                    if (text.isEmpty() || "null".equalsIgnoreCase(text)
                            || text.startsWith("http")) {
                        continue;
                    }
                    // A handle has no spaces; a full name is only a fallback.
                    if (fallback == null) {
                        fallback = text;
                    }
                    if (text.indexOf(' ') < 0 && text.length() <= 30) {
                        return text;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            } catch (Exception e) {
                // Try the next accessor.
            } catch (LinkageError e) {
                // Try the next accessor.
            }
        }
        return "instagram";
    }

    /**
     * Whether the model says the media carries audio.
     *
     * <p>Only a boolean accessor whose name says audio counts. Instagram's
     * media model has dozens of unrelated boolean accessors, so "any true
     * boolean" would claim audio on muted videos and ordinary photos.
     */
    private static boolean hasAudio(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || isObjectMethod(method)) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            if (!Boolean.class.equals(returned) && !boolean.class.equals(returned)) {
                continue;
            }
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("audio")) {
                continue;
            }
            try {
                Object value = method.invoke(model);
                if (value instanceof Boolean && (Boolean) value) {
                    return true;
                }
            } catch (Exception e) {
                // Try the next accessor.
            } catch (LinkageError e) {
                // Try the next accessor.
            }
        }
        return false;
    }

    /** Whether the method comes from {@link Object} itself. */
    private static boolean isObjectMethod(Method method) {
        return Object.class.equals(method.getDeclaringClass());
    }

    /**
     * The last part of a type name, without the nested-class part.
     *
     * <p>It accepts both a descriptor ({@code Lcom/instagram/Foo;}) and a Java
     * name ({@code java.lang.String}), and it drops the nested-class part of
     * a nested type ({@code com/example/Outer$Inner}).
     */
    private static String simpleName(String type) {
        if (type == null) {
            return "";
        }
        String name = type;
        if (name.endsWith(";")) {
            name = name.substring(0, name.length() - 1);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            name = name.substring(dot + 1);
        }
        int nested = name.lastIndexOf('$');
        if (nested >= 0 && nested + 1 < name.length()) {
            name = name.substring(nested + 1);
        }
        return name;
    }

    /** One candidate URL, with the width that came with it. */
    private static final class UrlCandidate {
        private final String url;
        private final int width;
        private final boolean preferred;

        UrlCandidate(String url, int width, boolean preferred) {
            this.url = url;
            this.width = width;
            this.preferred = preferred;
        }
    }
}
