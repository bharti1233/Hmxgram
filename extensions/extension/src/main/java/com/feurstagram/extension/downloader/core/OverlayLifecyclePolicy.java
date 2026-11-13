package com.feurstagram.extension.downloader.core;

/**
 * Decides whether the in-context chip may be attached to a window.
 *
 * <p>The chip lives in Instagram's window root, so attaching it needs three
 * things at once: the host bar must already be attached, the window root must
 * be a real {@code FrameLayout}, and there must be a UI context (an Activity)
 * to theme the chip from. Creating the chip earlier — or from a bare
 * application/system context — is what took the app down on the signed-out
 * launcher screen, so this policy is deliberately pessimistic: it waits, and
 * after a bounded number of attempts it gives up instead of forcing the view
 * into a window that is not ready.
 *
 * <p>Pure JDK on purpose: the rule is unit-tested without Android.
 */
public final class OverlayLifecyclePolicy {

    /** What the overlay should do on this attempt. */
    public enum Decision {
        /** The window is not ready yet; try again after the retry delay. */
        WAIT,
        /** Every prerequisite is satisfied; the chip may be created. */
        ATTACH,
        /** The attempts are exhausted; leave this window without a chip. */
        ABANDON
    }

    private OverlayLifecyclePolicy() {
    }

    /**
     * @param hostAttached        the tab bar is attached to a window
     * @param windowRootAvailable the root is the window's FrameLayout and is attached
     * @param uiContextAvailable  an Activity context was found for the window
     * @param attempt             zero-based attempt number
     * @param maxAttempts         how many attempts are allowed in total
     */
    public static Decision decide(boolean hostAttached, boolean windowRootAvailable,
            boolean uiContextAvailable, int attempt, int maxAttempts) {
        if (maxAttempts <= 0) {
            return Decision.ABANDON;
        }
        if (hostAttached && windowRootAvailable && uiContextAvailable) {
            return Decision.ATTACH;
        }
        int nextAttempt = Math.max(0, attempt) + 1;
        return nextAttempt >= maxAttempts ? Decision.ABANDON : Decision.WAIT;
    }
}
