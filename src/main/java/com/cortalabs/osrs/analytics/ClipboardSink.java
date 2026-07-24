/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

/**
 * A destination for "copy this text to the clipboard" that reports honestly whether
 * the copy actually happened. Existence of this seam is the whole point: the panel
 * must never tell the user "Copied to clipboard" when the clipboard did not take the
 * text (headless, a locked clipboard, a security manager). Implementations return
 * {@code true} only when the clipboard verifiably holds the exact string afterwards.
 */
interface ClipboardSink
{
	/**
	 * Attempt to place {@code text} on the system clipboard.
	 *
	 * @return {@code true} only if the clipboard verifiably holds {@code text} after
	 *     the write; {@code false} on any failure or mismatch. Never throws.
	 */
	boolean copy(String text);
}
