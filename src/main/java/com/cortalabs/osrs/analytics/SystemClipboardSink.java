/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * The production {@link ClipboardSink}: writes to the AWT system clipboard and then
 * reads it back to confirm the exact string landed before reporting success. That
 * readback is the honesty guarantee — {@code setContents} returns {@code void} and
 * cannot itself confirm the write, so we verify rather than assume.
 *
 * <p>Must be called on the EDT (the panel marshals onto it before copying). Every
 * failure mode — headless, a clipboard owned by another app, an unreadable flavor —
 * is swallowed into a {@code false} return; this sink never throws.
 */
final class SystemClipboardSink implements ClipboardSink
{
	@Override
	public boolean copy(String text)
	{
		if (text == null)
		{
			return false;
		}
		try
		{
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(new StringSelection(text), null);
			Object readback = clipboard.getData(DataFlavor.stringFlavor);
			return text.equals(readback);
		}
		catch (HeadlessException | IllegalStateException | IOException | UnsupportedFlavorException ex)
		{
			return false;
		}
	}
}
