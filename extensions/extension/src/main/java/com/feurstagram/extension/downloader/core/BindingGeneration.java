package com.feurstagram.extension.downloader.core;

/** Invalidates a pending confirmation on every rebind or detach, including A-B-A. */
public final class BindingGeneration {
    private long value;

    public long current() { return value; }
    public void invalidate() { value++; }
    public boolean matches(long captured) { return captured == value; }
}
