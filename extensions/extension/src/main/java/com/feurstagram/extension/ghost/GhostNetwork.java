package com.feurstagram.extension.ghost;

import com.feurstagram.extension.Config;

import java.io.IOException;
import java.net.URI;

/**
 * The network hook behind ghost mode.
 *
 * Instagram's network layer offers every outgoing request before it is sent.
 * Ghost mode uses that point to drop the receipts the user asked to hide: the
 * story view, the message read receipt and the typing indicator.
 *
 * Dropping a request is the same mechanism the content blocker uses: the
 * request fails as a network error, Instagram carries on, and nothing about
 * the account is corrupted. No message is delivered differently, no
 * notification is touched and no security event is intercepted.
 */
public final class GhostNetwork {

    private GhostNetwork() {
    }

    /**
     * Throws when the request must not leave the device.
     *
     * @param uri the request
     * @throws IOException when the request is suppressed
     */
    public static void throwIfSuppressed(URI uri) throws IOException {
        if (uri == null) return;
        android.content.Context context = Config.getAppContext();
        if (context == null) return;
        String path = uri.getPath();
        if (path == null) return;
        if (GhostMode.shouldSuppress(context, path)) {
            throw new IOException("HMX Insta ghost mode: request suppressed");
        }
    }
}
