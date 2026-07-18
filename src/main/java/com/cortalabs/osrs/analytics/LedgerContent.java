/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.SessionLedger.SkillGain;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Ledger tab's content: connection truth, the account being watched, the session
 * so far (with a per-skill XP breakdown), and progression at a glance. Extracted from
 * {@link AnalyticsPanel} so it carries no {@code PluginPanel}, tab, or transport
 * dependency and can be constructed and rendered headlessly under test.
 *
 * <p>The card stack is top-anchored: it sits in {@code BorderLayout.NORTH} so each
 * card takes exactly its content height and all leftover vertical space collects in
 * the empty centre — never inside a greedy first card. {@link #render} updates labels
 * in place and rebuilds the per-skill lines only when they change.
 */
final class LedgerContent extends JPanel
{
	private final JLabel connDot = new JLabel("●");
	private final JLabel connLine = new JLabel();
	private final JLabel sentValue = new JLabel();
	private final JLabel queueValue = new JLabel();
	private final JLabel backendValue = new JLabel();

	private final JLabel acctName = new JLabel();
	private final JLabel acctType = new JLabel();

	private final JPanel sessionGrid = PanelWidgets.grid();
	private final JLabel sessionEmpty = PanelWidgets.stewardLine();
	private final JLabel xpGainedValue = PanelWidgets.valueLabel();
	private final JLabel eventsValue = PanelWidgets.valueLabel();
	private final JPanel skillLines = new JPanel();

	private final JPanel progressionGrid = PanelWidgets.grid();
	private final JLabel progressionEmpty = PanelWidgets.stewardLine();
	private final JLabel questPointsValue = PanelWidgets.valueLabel();
	private final JLabel questsValue = PanelWidgets.valueLabel();
	private final JLabel diaryValue = PanelWidgets.valueLabel();

	private final JLabel versionValue = new JLabel();

	private final JPanel connectionCard;
	private final JPanel accountCard;
	private final JPanel sessionCard;
	private final JPanel progressionCard;
	private final JPanel footer;

	// Last-rendered structural state, so we revalidate only when a layout actually flips.
	private Boolean lastSessionKnown;
	private Boolean lastProgressionKnown;
	private Boolean lastQueueVisible;
	private AccountPresence lastPresence;
	private String lastGainsSignature = "";

	LedgerContent(Runnable onSend)
	{
		super(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		connectionCard = buildConnectionCard();
		accountCard = buildAccountCard();
		sessionCard = buildSessionCard();
		progressionCard = buildProgressionCard();
		footer = buildFooter(onSend);

		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		stack.add(connectionCard);
		stack.add(Box.createVerticalStrut(8));
		stack.add(accountCard);
		stack.add(Box.createVerticalStrut(8));
		stack.add(sessionCard);
		stack.add(Box.createVerticalStrut(8));
		stack.add(progressionCard);
		stack.add(Box.createVerticalStrut(10));
		stack.add(footer);

		// Pin the stack to the top; leftover height stays as empty centre, so no card
		// is ever stretched to fill the panel.
		add(stack, BorderLayout.NORTH);
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	private JPanel buildConnectionCard()
	{
		JPanel card = PanelWidgets.card();

		connDot.setForeground(PanelWidgets.IDLE);
		connLine.setFont(FontManager.getRunescapeBoldFont());
		connLine.setForeground(PanelWidgets.INK);
		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setBackground(card.getBackground());
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.add(connDot, BorderLayout.WEST);
		head.add(connLine, BorderLayout.CENTER);
		card.add(head);

		card.add(Box.createVerticalStrut(6));
		card.add(valueRow("Last sent", sentValue));

		queueValue.setFont(FontManager.getRunescapeSmallFont());
		queueValue.setForeground(PanelWidgets.INK_DIM);
		queueValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		queueValue.setBorder(new EmptyBorder(2, 0, 0, 0));
		card.add(queueValue);

		backendValue.setFont(FontManager.getRunescapeSmallFont());
		backendValue.setForeground(PanelWidgets.OFF);
		backendValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		backendValue.setBorder(new EmptyBorder(6, 0, 0, 0));
		card.add(backendValue);

		return card;
	}

	private JPanel buildAccountCard()
	{
		JPanel card = PanelWidgets.card();
		card.add(PanelWidgets.heading("Account"));
		card.add(Box.createVerticalStrut(4));

		acctName.setFont(FontManager.getRunescapeBoldFont());
		acctName.setForeground(PanelWidgets.INK);
		acctName.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(acctName);

		acctType.setFont(FontManager.getRunescapeSmallFont());
		acctType.setForeground(PanelWidgets.INK_DIM);
		acctType.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(acctType);

		return card;
	}

	private JPanel buildSessionCard()
	{
		JPanel card = PanelWidgets.card();
		card.add(PanelWidgets.heading("This session"));
		card.add(Box.createVerticalStrut(4));

		sessionGrid.add(PanelWidgets.keyLabel("XP gained"));
		sessionGrid.add(xpGainedValue);
		sessionGrid.add(PanelWidgets.keyLabel("Events sent"));
		sessionGrid.add(eventsValue);
		card.add(sessionGrid);

		skillLines.setLayout(new BoxLayout(skillLines, BoxLayout.Y_AXIS));
		skillLines.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		skillLines.setAlignmentX(Component.LEFT_ALIGNMENT);
		skillLines.setBorder(new EmptyBorder(4, 0, 0, 0));
		card.add(skillLines);

		card.add(sessionEmpty);
		return card;
	}

	private JPanel buildProgressionCard()
	{
		JPanel card = PanelWidgets.card();
		card.add(PanelWidgets.heading("At a glance"));
		card.add(Box.createVerticalStrut(4));

		progressionGrid.add(PanelWidgets.keyLabel("Quest points"));
		progressionGrid.add(questPointsValue);
		progressionGrid.add(PanelWidgets.keyLabel("Quests"));
		progressionGrid.add(questsValue);
		progressionGrid.add(PanelWidgets.keyLabel("Diary tiers"));
		progressionGrid.add(diaryValue);
		card.add(progressionGrid);
		card.add(progressionEmpty);

		return card;
	}

	private JPanel buildFooter(Runnable onSend)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARK_GRAY_COLOR);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton send = new JButton("Send now");
		send.setFocusPainted(false);
		send.setForeground(PanelWidgets.INK);
		send.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		send.setAlignmentX(Component.LEFT_ALIGNMENT);
		send.setMaximumSize(new Dimension(Integer.MAX_VALUE, send.getPreferredSize().height));
		if (onSend != null)
		{
			send.addActionListener(e -> onSend.run());
		}
		box.add(send);

		versionValue.setFont(FontManager.getRunescapeSmallFont());
		versionValue.setForeground(PanelWidgets.OFF);
		versionValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		versionValue.setBorder(new EmptyBorder(6, 0, 0, 0));
		box.add(versionValue);

		return box;
	}

	// ------------------------------------------------------------------
	// Render
	// ------------------------------------------------------------------

	/** Update every label from the model in place. Runs on the EDT (or headless in test). */
	void render(PanelModel model, ZoneId zone, long nowMs)
	{
		renderConnection(model, zone, nowMs);
		renderAccount(model);
		renderSession(model);
		renderProgression(model);
		versionValue.setText("Catherby v" + model.version);
	}

	private void renderConnection(PanelModel model, ZoneId zone, long nowMs)
	{
		connDot.setForeground(PanelWidgets.toneColor(model.connectionTone));
		connLine.setText(model.connectionLine);

		if (model.lastAcceptedMs <= 0L)
		{
			sentValue.setText("never");
		}
		else
		{
			sentValue.setText(PanelText.clock(model.lastAcceptedMs, zone)
				+ " · " + PanelText.since(model.lastAcceptedMs, nowMs));
		}

		boolean queueVisible = model.queueDepth > 0;
		queueValue.setText(queueVisible ? model.queueDepth + " waiting to send" : "");
		queueValue.setVisible(queueVisible);
		if (lastQueueVisible == null || lastQueueVisible != queueVisible)
		{
			lastQueueVisible = queueVisible;
			connectionCard.revalidate();
		}

		backendValue.setText(model.backendUrl.isEmpty() ? "No backend set" : model.backendUrl);
	}

	private void renderAccount(PanelModel model)
	{
		switch (model.presence)
		{
			case LIVE:
				acctName.setText(model.rsn);
				acctName.setForeground(PanelWidgets.INK);
				acctType.setText(model.accountType == null ? "" : model.accountType);
				acctType.setForeground(PanelWidgets.INK_DIM);
				acctType.setVisible(model.accountType != null);
				break;
			case AWAY:
				acctName.setText(model.rsn);
				acctName.setForeground(PanelWidgets.INK_DIM);
				acctType.setText(model.accountType == null ? "signed out" : model.accountType + " · signed out");
				acctType.setForeground(PanelWidgets.OFF);
				acctType.setVisible(true);
				break;
			case NONE:
			default:
				acctName.setText("No account witnessed yet.");
				acctName.setForeground(PanelWidgets.INK_DIM);
				acctType.setText("");
				acctType.setVisible(false);
				break;
		}
		if (model.presence != lastPresence)
		{
			lastPresence = model.presence;
			accountCard.revalidate();
			accountCard.repaint();
		}
	}

	private void renderSession(PanelModel model)
	{
		boolean known = model.sessionKnown;
		if (known)
		{
			xpGainedValue.setText(PanelText.integer(model.xpGained));
			eventsValue.setText(PanelText.integer(model.eventsSent));
			renderSkillLines(model.skillGains);
		}
		else
		{
			sessionEmpty.setText("Nothing recorded yet.");
			renderSkillLines(java.util.Collections.emptyList());
		}
		sessionGrid.setVisible(known);
		skillLines.setVisible(known);
		sessionEmpty.setVisible(!known);
		if (lastSessionKnown == null || lastSessionKnown != known)
		{
			lastSessionKnown = known;
			sessionCard.revalidate();
			sessionCard.repaint();
		}
	}

	/** Rebuild the per-skill breakdown, but only when the set of gains actually changed. */
	private void renderSkillLines(List<SkillGain> gains)
	{
		StringBuilder signature = new StringBuilder();
		for (SkillGain gain : gains)
		{
			signature.append(gain.skill).append(':').append(gain.gained).append('|');
		}
		if (signature.toString().equals(lastGainsSignature))
		{
			return;
		}
		lastGainsSignature = signature.toString();

		skillLines.removeAll();
		for (SkillGain gain : gains)
		{
			JPanel row = new JPanel(new BorderLayout());
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setBorder(new EmptyBorder(1, 0, 1, 0));

			JLabel name = new JLabel(capitalize(gain.skill));
			name.setFont(FontManager.getRunescapeSmallFont());
			name.setForeground(PanelWidgets.INK_DIM);
			row.add(name, BorderLayout.WEST);

			JLabel value = new JLabel("+" + PanelText.integer(gain.gained));
			value.setFont(FontManager.getRunescapeSmallFont());
			value.setForeground(PanelWidgets.OK);
			value.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
			row.add(value, BorderLayout.EAST);

			// Cap height only after the labels exist, so a row keeps its content height
			// (capping an empty row first would pin every line to a 2px sliver).
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			skillLines.add(row);
		}
		skillLines.revalidate();
		skillLines.repaint();
	}

	private void renderProgression(PanelModel model)
	{
		boolean known = model.progression.known;
		if (known)
		{
			questPointsValue.setText(PanelText.integer(model.progression.questPoints));
			questsValue.setText(PanelText.integer(model.progression.questsComplete));
			diaryValue.setText(PanelText.integer(model.progression.diaryTiersComplete)
				+ " / " + PanelText.integer(model.progression.diaryTiersTotal));
		}
		else
		{
			progressionEmpty.setText("Not witnessed yet.");
		}
		progressionGrid.setVisible(known);
		progressionEmpty.setVisible(!known);
		if (lastProgressionKnown == null || lastProgressionKnown != known)
		{
			lastProgressionKnown = known;
			progressionCard.revalidate();
			progressionCard.repaint();
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private JPanel valueRow(String name, JLabel value)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 0, 2, 0));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(name);
		label.setForeground(PanelWidgets.INK_DIM);
		panel.add(label, BorderLayout.WEST);
		value.setForeground(PanelWidgets.INK);
		value.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		panel.add(value, BorderLayout.EAST);
		return panel;
	}

	private static String capitalize(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "";
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(java.util.Locale.ENGLISH);
	}

	/** Package-private for the headless layout test: the cards in top-to-bottom order. */
	List<JComponent> cards()
	{
		List<JComponent> out = new ArrayList<>();
		out.add(connectionCard);
		out.add(accountCard);
		out.add(sessionCard);
		out.add(progressionCard);
		out.add(footer);
		return out;
	}

	/** Package-private for the headless layout test: how many per-skill lines are shown. */
	int renderedSkillLineCount()
	{
		return skillLines.getComponentCount();
	}
}
