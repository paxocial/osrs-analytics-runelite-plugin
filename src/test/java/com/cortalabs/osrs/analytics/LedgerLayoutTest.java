/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.SessionLedger.SkillGain;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Headless layout guard for the Ledger content. The v1.3.0 render shipped a greedy
 * Connection card that ate the whole sidebar height; a thin, untested Swing layer let
 * it through. This paints the real card stack offscreen at the sidebar width and
 * asserts the defect cannot come back: no card is ever taller than the rows it holds,
 * and the stack collects at the top rather than stretching to fill.
 *
 * <p>Also writes the rendered states to {@code build/panel-renders/} so the layout can
 * be inspected by eye, not just asserted.
 */
public class LedgerLayoutTest
{
	private static final int SIDEBAR_WIDTH = 225;
	private static final int TALL = 900;
	private static final long NOW = 1_000_000_000_000L;
	private static final File OUT = new File("build/panel-renders");

	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
		OUT.mkdirs();
	}

	/**
	 * THE greedy-card guard. Laid out in a sidebar far taller than its content, no card
	 * may exceed its own preferred height — the stack is top-anchored and each card is
	 * capped to its rows. Restore the old full-height BoxLayout body (or drop the card
	 * max-height cap) and the Connection card balloons to ~900px here -> this fails.
	 */
	@Test
	public void noCardStretchesPastItsContent()
	{
		LedgerContent ledger = laidOut(liveModel(), "live");

		for (JComponent card : ledger.cards())
		{
			int preferred = card.getPreferredSize().height;
			int actual = card.getHeight();
			assertTrue("a card must be laid out with real height", actual > 0);
			assertTrue("card grew past its content: actual=" + actual + " preferred=" + preferred,
				actual <= preferred + 2);
		}
	}

	/**
	 * The stack is top-anchored: the sum of the cards' heights leaves real empty space
	 * at the bottom of a tall sidebar, rather than the cards being stretched to fill it.
	 */
	@Test
	public void cardStackCollectsAtTheTopLeavingEmptySpaceBelow()
	{
		LedgerContent ledger = laidOut(liveModel(), "live-empty-space");

		int lowest = 0;
		for (JComponent card : ledger.cards())
		{
			// Each card's bottom edge in LedgerContent coordinates (its parent stack sits at y=0).
			int bottom = card.getParent().getY() + card.getY() + card.getHeight();
			lowest = Math.max(lowest, bottom);
		}
		assertTrue("the whole stack should occupy well under the sidebar height, leaving room below; "
			+ "lowest card bottom=" + lowest, lowest < TALL - 200);
	}

	@Test
	public void emptyStateAlsoLaysOutCompactly()
	{
		LedgerContent ledger = laidOut(emptyModel(), "empty");
		for (JComponent card : ledger.cards())
		{
			assertTrue("even the empty state keeps cards compact",
				card.getHeight() <= card.getPreferredSize().height + 2);
		}
		assertTrue("no account witnessed: no per-skill lines", ledger.renderedSkillLineCount() == 0);
	}

	/**
	 * The per-skill breakdown actually renders — one visible line per climbed skill, each
	 * with real height. Guards the row-height regression where a line collapsed to a 2px
	 * sliver (its max-height was pinned before its labels were added).
	 */
	@Test
	public void perSkillBreakdownRendersAVisibleLinePerClimbedSkill()
	{
		LedgerContent ledger = laidOut(liveModel(), "live-skills");
		assertTrue("two skills climbed -> two lines, actual=" + ledger.renderedSkillLineCount(),
			ledger.renderedSkillLineCount() == 2);

		JComponent sessionCard = ledger.cards().get(2);
		int gridOnly = laidOut(liveNoGainsModel(), "live-no-gains").cards().get(2).getPreferredSize().height;
		assertTrue("the session card must grow to hold the per-skill lines: withGains="
				+ sessionCard.getPreferredSize().height + " gridOnly=" + gridOnly,
			sessionCard.getPreferredSize().height > gridOnly + 10);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static LedgerContent laidOut(PanelModel model, String name)
	{
		LedgerContent ledger = new LedgerContent(() -> { });
		ledger.render(model, ZoneId.of("UTC"), NOW);
		ledger.setSize(SIDEBAR_WIDTH, TALL);
		layoutTree(ledger);
		writePng(ledger, name);
		return ledger;
	}

	private static void layoutTree(Component component)
	{
		if (component instanceof Container)
		{
			Container container = (Container) component;
			container.doLayout();
			for (Component child : container.getComponents())
			{
				layoutTree(child);
			}
		}
	}

	private static void writePng(LedgerContent ledger, String name)
	{
		try
		{
			BufferedImage image = new BufferedImage(SIDEBAR_WIDTH, TALL, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			ledger.printAll(g);
			g.dispose();
			ImageIO.write(image, "png", new File(OUT, "ledger-" + name + ".png"));
		}
		catch (Exception ex)
		{
			// The PNG is an inspection aid, not the assertion; a write failure must not
			// mask the layout checks. Surface it without failing the test.
			System.err.println("panel render write failed for " + name + ": " + ex.getMessage());
		}
	}

	private static PanelModel liveModel()
	{
		List<SkillGain> gains = Arrays.asList(
			new SkillGain("mining", 4_120L),
			new SkillGain("hitpoints", 540L));
		return PanelModel.of(
			AnalyticsClient.State.OK, true, 2, NOW - 8_000L, 228L, "http://localhost:8000/api/v1/plugin",
			AccountPresence.LIVE, "Seaking", net.runelite.api.vars.AccountType.NORMAL,
			4_660L, 2, gains, ProgressionReading.of(225, 114, 12, 48), "1.3.0");
	}

	private static PanelModel liveNoGainsModel()
	{
		return PanelModel.of(
			AnalyticsClient.State.OK, true, 0, NOW - 8_000L, 12L, "http://localhost:8000/api/v1/plugin",
			AccountPresence.LIVE, "Seaking", net.runelite.api.vars.AccountType.NORMAL,
			0L, 0, Collections.emptyList(), ProgressionReading.of(225, 114, 12, 48), "1.3.0");
	}

	private static PanelModel emptyModel()
	{
		return PanelModel.of(
			AnalyticsClient.State.IDLE, true, 0, 0L, 0L, "http://localhost:8000/api/v1/plugin",
			AccountPresence.NONE, null, null, 0L, 0, Collections.emptyList(),
			ProgressionReading.UNKNOWN, "1.3.0");
	}
}
