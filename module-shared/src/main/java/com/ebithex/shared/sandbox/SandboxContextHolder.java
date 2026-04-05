package com.ebithex.shared.sandbox;

/**
 * ThreadLocal carrier for the sandbox flag.
 *
 * Set by {@code ApiKeyAuthFilter} at the start of every API-key request,
 * cleared in its finally block. Defaults to {@code false} (prod) for any
 * thread that never calls {@link #set(boolean)} — scheduled jobs, startup, etc.
 */
public class SandboxContextHolder {

    private static final ThreadLocal<Boolean> CTX = new ThreadLocal<>();

    private SandboxContextHolder() {}

    public static void set(boolean sandbox) {
        CTX.set(sandbox);
    }

    public static boolean isSandbox() {
        return Boolean.TRUE.equals(CTX.get());
    }

    public static void clear() {
        CTX.remove();
    }
}
