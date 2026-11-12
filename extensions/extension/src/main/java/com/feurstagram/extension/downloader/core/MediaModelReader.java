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
 * for them by <em>type</em>:
 *
 * <ol>
 *   <li>the image accessor is the no-argument method whose result type is
 *       called {@code ImageInfo};</li>
 *   <li>the video accessor is the one whose result type is called
 *       {@code VideoInfo};</li>
 *   <li>the URL is the string the info object exposes that starts with
 *       {@code http};</li>
 *   <li>the children are the list the model exposes whose elements are
 *       models again.</li>
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

    /** The simple type name of the video info object. */
    public static final String VIDEO_INFO = "VideoInfo";

    private MediaModelReader() {
    }

    /**
     * Reads a media model.
     *
     * @param model the model Instagram handed to its row binder
     * @return what it describes, or {@code null} when it cannot be read
     */
    public static ObservedMedia read(Object model) {
        if (model == null) {
            return null;
        }
        try {
            return readModel(model, 0);
        } catch (RuntimeException e) {
            // A model that cannot be read is not an error.
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    private static ObservedMedia readModel(Object model, int depth) {
        if (model == null || depth > 3) {
            return null;
        }
        List<Object> children = childrenOf(model);
        if (!children.isEmpty()) {
            List<ObservedMedia> observed = new ArrayList<ObservedMedia>();
            for (Object child : children) {
                ObservedMedia media = readModel(child, depth + 1);
                if (media != null && media.isDownloadable()) {
                    observed.add(media);
                }
            }
            if (observed.isEmpty()) {
                return null;
            }
            return ObservedMedia.carousel(idOf(model), observed, ownerOf(model), 0L);
        }

        String imageUrl = urlOf(infoOf(model, IMAGE_INFO));
        String videoUrl = urlOf(infoOf(model, VIDEO_INFO));
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
            }
        }
        return null;
    }

    /**
     * The URL an info object exposes: the string it returns that looks like
     * a link. A width or a label is not accepted.
     */
    private static String urlOf(Object info) {
        if (info == null) {
            return null;
        }
        for (Method method : info.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (!String.class.equals(method.getReturnType())) {
                continue;
            }
            try {
                Object value = method.invoke(info);
                if (value instanceof String) {
                    String text = ((String) value).trim();
                    if (text.startsWith("http://") || text.startsWith("https://")) {
                        return text;
                    }
                }
            } catch (Exception e) {
                // Try the next accessor.
            }
        }
        return null;
    }

    /** The child models of a carousel, or an empty list. */
    private static List<Object> childrenOf(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
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
                if (entry != null && entry != model) {
                    models.add(entry);
                }
            }
            if (!models.isEmpty()) {
                return models;
            }
        }
        return new ArrayList<Object>();
    }

    /** The stable id of the model, when it exposes one. */
    private static String idOf(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (isObjectMethod(method)) {
                // toString() and friends would answer with the class name.
                continue;
            }
            String type = simpleName(method.getReturnType().getName());
            if (!"String".equals(type) && !"Long".equals(type)
                    && !"Integer".equals(type)) {
                continue;
            }
            try {
                Object value = method.invoke(model);
                if (value == null) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                if (!text.isEmpty() && !text.startsWith("http")) {
                    return text;
                }
            } catch (Exception e) {
                // Try the next accessor.
            }
        }
        return null;
    }

    /** The owner of the media, when the model exposes one. */
    private static String ownerOf(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
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
                for (Method getter : user.getClass().getMethods()) {
                    if (getter.getParameterTypes().length != 0) {
                        continue;
                    }
                    if (!String.class.equals(getter.getReturnType())) {
                        continue;
                    }
                    Object value = getter.invoke(user);
                    if (value instanceof String && !((String) value).trim().isEmpty()) {
                        return (String) value;
                    }
                }
            } catch (Exception e) {
                // Try the next accessor.
            }
        }
        return "instagram";
    }

    /** Whether the model says the original media carries audio. */
    private static boolean hasAudio(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (isObjectMethod(method)) {
                continue;
            }
            Class<?> type = method.getReturnType();
            if (!Boolean.class.equals(type) && !boolean.class.equals(type)) {
                continue;
            }
            try {
                Object value = method.invoke(model);
                if (value instanceof Boolean && (Boolean) value) {
                    return true;
                }
            } catch (Exception e) {
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

    /** Kept for the tests: the fields of a fake model. */
    static String fieldOf(Object model, String name) {
        try {
            Field field = model.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(model);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}
