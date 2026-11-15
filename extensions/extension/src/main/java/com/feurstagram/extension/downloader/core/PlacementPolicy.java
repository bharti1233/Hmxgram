package com.feurstagram.extension.downloader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the in-context download control may be placed, as a pure decision.
 *
 * <p>M11 decided placement inside the Android code, so a refusal could only be
 * observed on a device. This class is the same decision as a pure function of
 * a small, host-agnostic description of the views. It is used in M12 in
 * <em>shadow mode</em>: the Android layer still places exactly as before, and
 * also evaluates this policy and logs whether the two agree. That is how the
 * policy is proven against real layouts without changing any placement.
 *
 * <p>The rules are deliberately conservative:
 *
 * <ul>
 *   <li>Feed: the bookmark must live in a visible, horizontal, left-to-right
 *       container whose child order is meaningful. A {@code ConstraintLayout}
 *       has no child order, so it is refused instead of guessed.</li>
 *   <li>Reels: the candidate slots are the upper-right and the centre-right of
 *       the native action bar. A slot is refused when it leaves the padding
 *       box or overlaps a blocking sibling.</li>
 *   <li>A visible but undecorated container is transparent to overlap: its own
 *       bounds do not block, but each visible descendant is still tested. This
 *       keeps a full-width transparent wrapper from hiding a free corner while
 *       still protecting every real control inside it.</li>
 * </ul>
 *
 * <p>No Android import, no time, no randomness, no global state: the same
 * input always produces the same result.
 *
 * <p>Pure-Java core: no Android imports.
 */
public final class PlacementPolicy {

    /** The surface a placement belongs to. */
    public enum Surface { FEED, REELS }

    /** How a container orders its children. */
    public enum Axis { NONE, HORIZONTAL, VERTICAL }

    /** The slot a Reels control was placed in. */
    public enum Anchor { NONE, END_TOP, END_CENTER }

    /**
     * A container the Reels slot may be measured inside.
     *
     * <p>M11 accepted only a {@code FrameLayout}. Device evidence (M12
     * addendum) showed the native bar is a {@code RelativeLayout} subclass, so
     * the decision now names the container instead of guessing from geometry.
     */
    public enum Container { FRAME, RELATIVE, UNSUPPORTED }

    /**
     * Why a placement was refused.
     *
     * <p>Every value is a stable, privacy-safe reason code: it names a
     * structural condition, never text, a URL or an account.
     */
    public enum Reason {
        NONE,
        PARENT_MISSING,
        PARENT_INVISIBLE,
        PARENT_VERTICAL,
        PARENT_RTL,
        PARENT_NOT_ORDERED,
        PARENT_NOT_MEASURED,
        BOOKMARK_MISSING,
        BOOKMARK_INVISIBLE,
        BOOKMARK_DETACHED,
        ICON_UNMEASURED,
        NO_HORIZONTAL_ROOM,
        BAR_MISSING,
        BAR_INVISIBLE,
        BAR_DETACHED,
        BAR_NOT_MEASURED,
        OUT_OF_BOUNDS,
        OVERLAP,
        ANCHOR_TYPE
    }

    /** How many blocking descendants are considered; a bar has few. */
    private static final int MAX_BLOCKERS = 64;

    /** How deep the blocker walk may go. */
    private static final int MAX_DEPTH = 6;

    private PlacementPolicy() {
    }

    /**
     * One view, described without Android.
     *
     * <p>All coordinates and sizes are integer pixels and are relative to the
     * parent, exactly like {@code View.getLeft()} and {@code getWidth()}.
     */
    public static final class Node {
        private final String type;
        private final Axis axis;
        private final boolean leftToRight;
        private final boolean orderedChildren;
        private final boolean visible;
        private final boolean attached;
        private final boolean blocks;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int paddingLeft;
        private final int paddingRight;
        private final int paddingTop;
        private final int paddingBottom;
        private final float alpha;
        private final List<Node> children;

        private Node(Builder builder) {
            this.type = builder.type;
            this.axis = builder.axis;
            this.leftToRight = builder.leftToRight;
            this.orderedChildren = builder.orderedChildren;
            this.visible = builder.visible;
            this.attached = builder.attached;
            this.blocks = builder.blocks;
            this.x = builder.x;
            this.y = builder.y;
            this.width = builder.width;
            this.height = builder.height;
            this.paddingLeft = builder.paddingLeft;
            this.paddingRight = builder.paddingRight;
            this.paddingTop = builder.paddingTop;
            this.paddingBottom = builder.paddingBottom;
            this.alpha = builder.alpha;
            this.children = Collections.unmodifiableList(
                    new ArrayList<Node>(builder.children));
        }

        /** A short, non-identifying type name, for a log line. */
        public String type() {
            return type;
        }

        /** How this node orders its children. */
        public Axis axis() {
            return axis;
        }

        /** Whether this node is visible. */
        public boolean visible() {
            return visible;
        }

        /** Whether this node is attached to a window. */
        public boolean attached() {
            return attached;
        }

        /** Whether this node is a real control rather than a wrapper. */
        public boolean blocks() {
            return blocks;
        }

        /** Width in pixels. */
        public int width() {
            return width;
        }

        /** Height in pixels. */
        public int height() {
            return height;
        }

        /** The children, in drawing order. */
        public List<Node> children() {
            return children;
        }

        private Node translated(int offsetX, int offsetY) {
            Builder copy = new Builder();
            copy.type = type;
            copy.axis = axis;
            copy.leftToRight = leftToRight;
            copy.orderedChildren = orderedChildren;
            copy.visible = visible;
            copy.attached = attached;
            copy.blocks = blocks;
            copy.x = offsetX + x;
            copy.y = offsetY + y;
            copy.width = width;
            copy.height = height;
            copy.paddingLeft = paddingLeft;
            copy.paddingRight = paddingRight;
            copy.paddingTop = paddingTop;
            copy.paddingBottom = paddingBottom;
            copy.alpha = alpha;
            copy.children = new ArrayList<Node>(children);
            return new Node(copy);
        }
    }

    /** Builds an immutable {@link Node}. */
    public static final class Builder {
        private String type = "";
        private Axis axis = Axis.NONE;
        private boolean leftToRight = true;
        private boolean orderedChildren;
        private boolean visible = true;
        private boolean attached = true;
        private boolean blocks;
        private int x;
        private int y;
        private int width;
        private int height;
        private int paddingLeft;
        private int paddingRight;
        private int paddingTop;
        private int paddingBottom;
        private float alpha = 1f;
        private List<Node> children = new ArrayList<Node>();

        private Builder() {
        }

        /** The display type, for diagnostics only. Never compared. */
        public Builder type(String value) {
            this.type = value == null ? "" : value;
            return this;
        }

        /** How the node orders its children. */
        public Builder axis(Axis value) {
            this.axis = value == null ? Axis.NONE : value;
            return this;
        }

        /** Whether the children run left to right. */
        public Builder leftToRight(boolean value) {
            this.leftToRight = value;
            return this;
        }

        /** Whether child index determines horizontal position. */
        public Builder orderedChildren(boolean value) {
            this.orderedChildren = value;
            return this;
        }

        /** Whether the node is visible. */
        public Builder visible(boolean value) {
            this.visible = value;
            return this;
        }

        /** Whether the node is attached to a window. */
        public Builder attached(boolean value) {
            this.attached = value;
            return this;
        }

        /** Whether the node is a real control rather than a wrapper. */
        public Builder blocks(boolean value) {
            this.blocks = value;
            return this;
        }

        /** Position inside the parent. */
        public Builder at(int left, int top) {
            this.x = left;
            this.y = top;
            return this;
        }

        /** The measured size. */
        public Builder size(int w, int h) {
            this.width = w;
            this.height = h;
            return this;
        }

        /** The padding box. */
        public Builder padding(int left, int top, int right, int bottom) {
            this.paddingLeft = left;
            this.paddingTop = top;
            this.paddingRight = right;
            this.paddingBottom = bottom;
            return this;
        }

        /** The drawing alpha. */
        public Builder alpha(float value) {
            this.alpha = value;
            return this;
        }

        /** Adds a child, in drawing order. */
        public Builder child(Node node) {
            if (node != null) {
                children.add(node);
            }
            return this;
        }

        /** Freezes the node. */
        public Node build() {
            return new Node(this);
        }
    }

    /** Starts a node description. */
    public static Builder node() {
        return new Builder();
    }

    /** A placement outcome. */
    public static final class Result {
        private final boolean placed;
        private final Reason reason;
        private final Anchor anchor;
        private final String blocker;

        private Result(boolean placed, Reason reason, Anchor anchor, String blocker) {
            this.placed = placed;
            this.reason = reason;
            this.anchor = anchor;
            this.blocker = blocker;
        }

        /** Whether the control may be shown at the returned anchor. */
        public boolean placed() {
            return placed;
        }

        /** Why it was refused, or {@link Reason#NONE}. */
        public Reason reason() {
            return reason;
        }

        /** Where it may be shown, or {@link Anchor#NONE}. */
        public Anchor anchor() {
            return anchor;
        }

        /** The type of the first blocking sibling, or an empty string. */
        public String blocker() {
            return blocker;
        }

        /** A stable token for a diagnostic line. */
        public String code() {
            return placed ? "PLACE:" + anchor.name() : "REFUSE:" + reason.name();
        }
    }

    /**
     * Decides the Feed placement.
     *
     * @param parent     the bookmark's parent, or {@code null}
     * @param bookmark   the native bookmark view, or {@code null}
     * @param iconSizePx the width the control needs, in pixels
     */
    public static Result feed(Node parent, Node bookmark, int iconSizePx) {
        if (parent == null) {
            return refuse(Reason.PARENT_MISSING);
        }
        if (!parent.visible) {
            return refuse(Reason.PARENT_INVISIBLE);
        }
        if (bookmark == null) {
            return refuse(Reason.BOOKMARK_MISSING);
        }
        if (parent.axis != Axis.HORIZONTAL) {
            return refuse(Reason.PARENT_VERTICAL);
        }
        if (!parent.leftToRight) {
            return refuse(Reason.PARENT_RTL);
        }
        if (!parent.orderedChildren) {
            return refuse(Reason.PARENT_NOT_ORDERED);
        }
        if (!bookmark.visible) {
            return refuse(Reason.BOOKMARK_INVISIBLE);
        }
        if (!bookmark.attached) {
            return refuse(Reason.BOOKMARK_DETACHED);
        }
        if (parent.width <= 0 || parent.height <= 0) {
            return refuse(Reason.PARENT_NOT_MEASURED);
        }
        if (iconSizePx <= 0) {
            return refuse(Reason.ICON_UNMEASURED);
        }
        int room = parent.width - parent.paddingLeft - parent.paddingRight - iconSizePx;
        if (room < 0) {
            return refuse(Reason.NO_HORIZONTAL_ROOM);
        }
        return place(Anchor.NONE);
    }

    /**
     * Decides the Reels placement inside the native action bar.
     *
     * @param bar       the action bar, or {@code null}
     * @param iconWidth the control width, in pixels
     * @param iconHeight the control height, in pixels
     */
    public static Result reels(Node bar, int iconWidth, int iconHeight) {
        return reels(bar, iconWidth, iconHeight, Container.FRAME);
    }

    /**
     * Decides the Reels placement inside a known container kind.
     *
     * <p>A container whose child layout the adapter cannot drive is refused
     * rather than guessed: an unsupported parent would place the control at
     * an arbitrary position inside Instagram's own bar.
     *
     * @param bar       the action bar, or {@code null}
     * @param iconWidth the control width, in pixels
     * @param iconHeight the control height, in pixels
     * @param container the container kind, or {@code null} for unsupported
     */
    public static Result reels(Node bar, int iconWidth, int iconHeight,
            Container container) {
        if (bar == null) {
            return refuse(Reason.BAR_MISSING);
        }
        if (container == null || container == Container.UNSUPPORTED) {
            return refuse(Reason.ANCHOR_TYPE);
        }
        if (!bar.visible) {
            return refuse(Reason.BAR_INVISIBLE);
        }
        if (!bar.attached) {
            return refuse(Reason.BAR_DETACHED);
        }
        if (iconWidth <= 0 || iconHeight <= 0) {
            return refuse(Reason.ICON_UNMEASURED);
        }
        if (bar.width <= 0 || bar.height <= 0) {
            return refuse(Reason.BAR_NOT_MEASURED);
        }
        List<Node> blockers = blockers(bar);
        boolean sawOverlap = false;
        String firstBlocker = "";
        Anchor[] order = {Anchor.END_TOP, Anchor.END_CENTER};
        for (Anchor anchor : order) {
            int right = bar.width - bar.paddingRight;
            int left = right - iconWidth;
            int top = anchor == Anchor.END_TOP
                    ? bar.paddingTop
                    : (bar.height - iconHeight) / 2;
            int bottom = top + iconHeight;
            if (left < bar.paddingLeft || top < bar.paddingTop
                    || right > bar.width - bar.paddingRight
                    || bottom > bar.height - bar.paddingBottom) {
                continue;
            }
            String blocker = firstBlocker(blockers, left, top, right, bottom);
            if (blocker == null) {
                return place(anchor);
            }
            sawOverlap = true;
            if (firstBlocker.isEmpty()) {
                firstBlocker = blocker;
            }
        }
        if (sawOverlap) {
            return new Result(false, Reason.OVERLAP, Anchor.NONE, firstBlocker);
        }
        return refuse(Reason.OUT_OF_BOUNDS);
    }

    /**
     * The visible descendants that can block a slot, in drawing order.
     *
     * <p>A visible node that is a control, or a leaf, blocks with its own
     * bounds. A visible node that is only a wrapper does not block by itself,
     * but every visible descendant is still collected. A node that is hidden
     * hides its whole subtree, which matches what a user can see.
     */
    public static List<Node> blockers(Node root) {
        List<Node> collected = new ArrayList<Node>();
        if (root != null) {
            collect(root, 0, 0, 0, collected);
        }
        return collected;
    }

    private static void collect(Node parent, int offsetX, int offsetY, int depth,
            List<Node> out) {
        if (out.size() >= MAX_BLOCKERS || depth >= MAX_DEPTH) {
            return;
        }
        for (Node child : parent.children) {
            if (!child.visible || child.alpha <= 0f) {
                continue;
            }
            int x = offsetX + child.x;
            int y = offsetY + child.y;
            boolean blocks = child.blocks || child.children.isEmpty();
            if (blocks) {
                out.add(child.translated(x, y));
                if (out.size() >= MAX_BLOCKERS) {
                    return;
                }
            } else {
                collect(child, x, y, depth + 1, out);
            }
        }
    }

    private static String firstBlocker(List<Node> blockers, int left, int top,
            int right, int bottom) {
        for (Node blocker : blockers) {
            int otherRight = blocker.x + blocker.width;
            int otherBottom = blocker.y + blocker.height;
            if (left < otherRight && blocker.x < right
                    && top < otherBottom && blocker.y < bottom) {
                return blocker.type;
            }
        }
        return null;
    }

    private static Result place(Anchor anchor) {
        return new Result(true, Reason.NONE, anchor, "");
    }

    private static Result refuse(Reason reason) {
        return new Result(false, reason, Anchor.NONE, "");
    }
}
