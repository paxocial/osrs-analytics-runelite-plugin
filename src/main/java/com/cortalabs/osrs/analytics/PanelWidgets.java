/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Shared Swing widget factories and colours for the Catherby panel, so the Ledger and
 * Lookup surfaces build from one vocabulary rather than duplicating helpers.
 *
 * <p>The important one is {@link #card()}: it caps its own maximum height to its
 * preferred height, so a card can never stretch past the rows it holds. That is the
 * fix for the greedy-card defect — every card takes exactly the space its content
 * needs, and leftover vertical space collects at the bottom of the stack, not inside
 * the first card.
 */
final class PanelWidgets
{
	private PanelWidgets()
	{
	}

	static final Color OK = new Color(76, 175, 80);
	static final Color WARN = new Color(255, 179, 0);
	static final Color ERROR = new Color(229, 57, 53);
	static final Color IDLE = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color OFF = new Color(124, 118, 110);
	static final Color INK = Color.WHITE;
	static final Color INK_DIM = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	static Color toneColor(PanelText.Tone tone)
	{
		switch (tone)
		{
			case OK:
				return OK;
			case WARN:
				return WARN;
			case ERROR:
				return ERROR;
			case OFF:
				return OFF;
			case IDLE:
			default:
				return IDLE;
		}
	}

	/**
	 * A bordered card that never grows past its content. Its maximum height tracks its
	 * preferred height, so in a vertical {@code BoxLayout} it claims exactly the rows it
	 * holds and no more.
	 */
	static JPanel card()
	{
		JPanel panel = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/** A two-column key/value grid on the card background. */
	static JPanel grid()
	{
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 3));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		return grid;
	}

	/** A small accent-coloured section heading. */
	static JLabel heading(String text)
	{
		JLabel label = new JLabel(text.toUpperCase());
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ACCENT);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** A dim left-aligned key label. */
	static JLabel keyLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(INK_DIM);
		return label;
	}

	/** A right-aligned white value label (empty text). */
	static JLabel valueLabel()
	{
		return valueLabel("");
	}

	/** A right-aligned white value label. */
	static JLabel valueLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(INK);
		label.setHorizontalAlignment(SwingConstants.RIGHT);
		return label;
	}

	/** A dim, left-aligned line in the steward's voice for empty/absent states. */
	static JLabel stewardLine()
	{
		JLabel label = new JLabel();
		label.setForeground(INK_DIM);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/**
	 * A left-aligned status label whose text soft-wraps to the sidebar width, for the
	 * tab action messages (loading / done / honest error). Pair its text with
	 * {@link #wrap(String)} so a long sentence flows rather than clipping.
	 */
	static JLabel wrappingLabel()
	{
		JLabel label = new JLabel();
		label.setForeground(INK_DIM);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** Wrap plain text so a {@link JLabel} soft-wraps at roughly the sidebar width. */
	static String wrap(String text)
	{
		return "<html><body style='width:185px'>" + escapeHtml(text) + "</body></html>";
	}

	/** Map an on-demand action failure onto a tone: actionable states warn, faults error. */
	static Color outcomeTone(ActionOutcome outcome)
	{
		if (outcome == null)
		{
			return ERROR;
		}
		switch (outcome)
		{
			case NOT_CONFIGURED:
			case UNAUTHORIZED:
			case NOT_FOUND:
			case RATE_LIMITED:
				return WARN;
			case UNAVAILABLE:
			case NETWORK:
			default:
				return ERROR;
		}
	}

	private static String escapeHtml(String text)
	{
		return text == null
			? ""
			: text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
