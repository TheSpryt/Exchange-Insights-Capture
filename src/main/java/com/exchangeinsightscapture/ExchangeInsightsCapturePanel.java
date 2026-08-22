package com.exchangeinsightscapture;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: what the plugin is doing right now, a one-click mode switch, the
 * Exchange Insights link status, and a shortcut into the plugin's own settings.
 *
 * <p>Styling follows the same family as the Bank Templates panel - rounded filled
 * status buttons in the RuneScape bold font - so the two plugins read as a set.
 */
class ExchangeInsightsCapturePanel extends PluginPanel
{
	private static final Color GREEN = new Color(35, 78, 42);
	private static final Color RED = new Color(94, 44, 44);
	private static final Color AMBER = new Color(96, 74, 30);
	private static final Color NEUTRAL = new Color(60, 60, 60);
	private static final Color MUTED = new Color(0x9E, 0x9E, 0x9E);
	/** The gold the sibling panels use for their chevrons and view actions. */
	private static final Color ICON_GOLD = new Color(0xE8, 0xC0, 0x50);

	private final ExchangeInsightsCapturePlugin plugin;
	private final ExchangeInsightsCaptureConfig config;
	private final ConfigManager configManager;
	private final ClipUploader uploader;

	private final JPanel statusRow = new JPanel();
	private final JPanel modeRow = new JPanel(new GridLayout(1, 3, 4, 0));
	private final JPanel bindRow = new JPanel();
	private final JPanel accountRow = new JPanel();
	private final ClipListPanel clipList;
	/** Same component the Bank Templates panel uses, so the two plugins look like a set. */
	private final SearchBar search = new SearchBar();
	private final javax.swing.JComboBox<String> sort =
		new javax.swing.JComboBox<>(new String[]{"Newest first", "Oldest first"});

	/** True while the bind button is armed and swallowing the next keypress. */
	private boolean listeningForKey;
	/** True while a browser device-link is in flight. */
	private boolean linking;

	ExchangeInsightsCapturePanel(ExchangeInsightsCapturePlugin plugin, ExchangeInsightsCaptureConfig config,
		ConfigManager configManager, ClipUploader uploader,
		java.util.concurrent.ScheduledExecutorService executor)
	{
		// Don't let PluginPanel wrap us in its own scrollpane - we manage our own so the
		// settings button can stay pinned to the bottom while only the content scrolls.
		// Same arrangement as the Bank Templates panel.
		super(false);
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.uploader = uploader;
		this.executor = executor;
		this.clipList = new ClipListPanel(config, this::refreshClips, executor,
			() ->
			{
				final ClipRecorder r = plugin.getRecorder();
				return r == null ? java.util.Collections.emptyList() : r.getPending();
			},
			(id, newName) ->
			{
				final ClipRecorder r = plugin.getRecorder();
				if (r != null)
				{
					r.renamePending(id, newName);
				}
			},
			id ->
			{
				final ClipRecorder r = plugin.getRecorder();
				if (r != null)
				{
					r.cancelPending(id);
				}
			},
			uploader);

		// Narrower side padding than the default: with our own always-on scrollbar, 10px each
		// side is what pushed content past the right edge.
		setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		// A plain JPanel in a JScrollPane keeps its OWN preferred width. Anything wider than the
		// viewport - a long label on one line, three mode buttons side by side - then made the
		// view wider than the visible area and the right-hand edge was simply clipped, which is
		// what kept pushing the mode buttons off screen. Tracking the viewport width forces the
		// content to the visible width instead, so labels wrap and rows shrink to fit.
		final JPanel body = new ScrollableColumn();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Account first, as in the Bank Templates panel - it is the thing users look for.
		accountRow.setLayout(new BoxLayout(accountRow, BoxLayout.Y_AXIS));
		accountRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		accountRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(accountRow);
		body.add(Box.createVerticalStrut(12));

		statusRow.setLayout(new BoxLayout(statusRow, BoxLayout.Y_AXIS));
		statusRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(statusRow);

		body.add(Box.createVerticalStrut(10));
		body.add(sectionLabel("Capture mode"));
		body.add(Box.createVerticalStrut(4));
		modeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		modeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(modeRow);

		// No leading gap here: the bind row supplies its own when it has something to show, and
		// it is empty in every mode but Manual.
		bindRow.setLayout(new BoxLayout(bindRow, BoxLayout.Y_AXIS));
		bindRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bindRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(bindRow);

		// One gap, not three. This was 6 + 14 stacked on top of the leading strut above, so in
		// Automatic mode - where the bind row renders nothing at all - it left 26 pixels of empty
		// space between the mode buttons and the clips below.
		body.add(Box.createVerticalStrut(14));
		body.add(sectionLabel("Clips"));
		body.add(Box.createVerticalStrut(4));

		// Search and sort live out here rather than inside the list, because the list tears itself
		// down and rebuilds on every refresh - and the poll refreshes it while you are typing.
		// Rebuilt controls would lose the caret and whatever had been typed so far.
		search.setAlignmentX(Component.LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		search.setPreferredSize(new Dimension(100, 28));
		search.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				clipList.setFilter(search.getText());
			}
		});
		search.addClearListener(() -> clipList.setFilter(""));
		body.add(search);
		body.add(Box.createVerticalStrut(4));

		styleCombo(sort);
		sort.setAlignmentX(Component.LEFT_ALIGNMENT);
		sort.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		sort.addActionListener(e -> clipList.setNewestFirst(sort.getSelectedIndex() == 0));
		body.add(sort);
		body.add(Box.createVerticalStrut(6));

		clipList.setAlignmentX(Component.LEFT_ALIGNMENT);
		// Unbounded height so BoxLayout hands the leftover viewport space to the clip list rather
		// than sharing it out above; the list then has room to push its bottom pager down.
		clipList.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		body.add(clipList);

		// Content scrolls; the settings button does not. The vertical bar is ALWAYS shown so
		// card widths do not shift as the list grows past the fold.
		final javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(body,
			javax.swing.JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
			javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		ThinScrollBarUI.style(scroll);
		add(scroll, BorderLayout.CENTER);

		final JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		final JButton settings = styledButton("Open plugin settings", NEUTRAL);
		settings.setToolTipText("Opens this plugin's configuration page.");
		settings.setAlignmentX(Component.LEFT_ALIGNMENT);
		settings.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		settings.addActionListener(e -> plugin.openConfigPanel());
		south.add(settings);

		add(south, BorderLayout.SOUTH);

		// The list tells us when the account changed; that is when the usage figures are stale.
		clipList.setQuotaListener(this::refreshQuota);
		// Upload progress arrives per 64KB chunk; repainting on each would flood the EDT, so
		// the list is redrawn at most a few times a second.
		uploader.setProgressListener(this::onUploadProgress);

		refresh();
		refreshClips();
		refreshQuota();
		refreshCloud();
	}

	/**
	 * Dress a combo the way the Bank Templates panel dresses its sort dropdowns: rounded body, gold
	 * chevron, RuneScape font, and a popup whose rows match the panel rather than the system theme.
	 */
	private static void styleCombo(javax.swing.JComboBox<String> combo)
	{
		combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				final JButton arrow = new JButton()
				{
					@Override
					protected void paintComponent(Graphics g)
					{
						final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
						g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
							java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
						g2.setColor(ICON_GOLD);
						g2.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND,
							java.awt.BasicStroke.JOIN_ROUND));
						final int cx = getWidth() / 2;
						final int cy = getHeight() / 2;
						g2.drawLine(cx - 4, cy - 2, cx, cy + 2);
						g2.drawLine(cx + 4, cy - 2, cx, cy + 2);
						g2.dispose();
					}
				};
				arrow.setBorder(BorderFactory.createEmptyBorder());
				arrow.setContentAreaFilled(false);
				arrow.setFocusable(false);
				return arrow;
			}

			// Non-opaque so the body can be painted with the cards' rounded corners instead of the
			// square fill the look-and-feel would draw.
			@Override
			public void paintCurrentValueBackground(Graphics g, java.awt.Rectangle bounds, boolean hasFocus)
			{
				RoundedBorder.fill(g, comboBox, comboBox.getBackground());
			}
		});
		combo.setOpaque(false);
		combo.setFocusable(false);
		combo.setFont(FontManager.getRunescapeFont());
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setForeground(Color.WHITE);
		combo.setBorder(new RoundedBorder(ColorScheme.MEDIUM_GRAY_COLOR, new Insets(3, 8, 3, 3)));
		combo.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
				int index, boolean selected, boolean focused)
			{
				final JLabel row = (JLabel) super.getListCellRendererComponent(list, value, index,
					selected, focused);
				row.setFont(FontManager.getRunescapeFont());
				row.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
				row.setBackground(selected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
				row.setForeground(selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
				return row;
			}
		});
	}

	/** Called by the plugin as the link flow progresses, so the button reflects it. */
	void setLinking(boolean value)
	{
		linking = value;
		refresh();
	}

	/** How often the open panel re-reads the server. Cheap: one small request, and none at all
	 *  when the account is not linked, since the listing short-circuits without a token. */
	private static final int POLL_SECONDS = 20;

	private final java.util.concurrent.ScheduledExecutorService executor;
	private java.util.concurrent.ScheduledFuture<?> poll;

	private long lastProgressPaintMs;

	private void onUploadProgress()
	{
		final long now = System.currentTimeMillis();
		if (now - lastProgressPaintMs < 250)
		{
			return;
		}
		lastProgressPaintMs = now;
		SwingUtilities.invokeLater(this::refreshClips);
	}

	/** Re-read the clip folder and redraw the list. */
	void refreshClips()
	{
		clipList.refresh();
		revalidate();
		repaint();
	}

	/** Re-ask the server for the allowance, then redraw. Cheap and only on state changes. */
	void refreshQuota()
	{
		uploader.refreshQuota(() -> SwingUtilities.invokeLater(this::refresh));
	}

	/** Re-read both the account's clip list and its usage. */
	void refreshCloud()
	{
		clipList.refreshRemote();
	}

	/**
	 * Keep the list current while the user is looking at it.
	 *
	 * <p>Clips are not only created here. One can be deleted from the website or another client,
	 * or dropped server-side when the account goes over its allowance, and none of that reaches
	 * this client on its own - so before these hooks the panel showed whatever was true when the
	 * client started. Opening the tab now re-reads immediately, and a poll keeps it honest for as
	 * long as it stays open.
	 */
	@Override
	public void onActivate()
	{
		refreshCloud();
		if (poll == null || poll.isCancelled())
		{
			poll = executor.scheduleWithFixedDelay(
				() -> SwingUtilities.invokeLater(clipList::refreshRemoteIfChanged),
				POLL_SECONDS, POLL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/** Closed tab, no polling: an unopened panel should cost nothing. */
	@Override
	public void onDeactivate()
	{
		if (poll != null)
		{
			poll.cancel(false);
			poll = null;
		}
	}

	/** Rebuild the dynamic rows from current state. Must be called on the EDT. */
	void refresh()
	{
		refreshStatus();
		refreshModes();
		refreshBind();
		refreshAccount();
		revalidate();
		repaint();
	}

	/**
	 * The arm-hotkey bind control. RuneLite's own keybind widget in the config panel gives no
	 * visible sign that it is waiting for a key, which is exactly how a bind silently fails to
	 * take - so this one says so, in amber, and stays armed until a real key arrives.
	 */
	private void refreshBind()
	{
		bindRow.removeAll();
		if (config.captureMode() != CaptureMode.MANUAL)
		{
			return;
		}

		bindRow.add(Box.createVerticalStrut(6));

		final Keybind current = config.manualToggleHotkey();
		final boolean bound = current != null && current.getKeyCode() != 0;

		final JButton bind = styledButton(
			listeningForKey ? "Press any key..." : (bound ? "Arm hotkey: " + current : "Arm hotkey: click to set"),
			listeningForKey ? AMBER : (bound ? GREEN : RED));
		bind.setFont(FontManager.getRunescapeSmallFont());
		bind.setToolTipText(listeningForKey
			? "Listening - press the key you want, or Escape to cancel."
			: "Click, then press the key you want to bind.");
		bind.setAlignmentX(Component.LEFT_ALIGNMENT);
		bind.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		bind.setFocusable(true);

		bind.addActionListener(e ->
		{
			listeningForKey = true;
			refresh();
			bind.requestFocusInWindow();
		});

		// Consume the raw key event rather than relying on the action map, so modifiers and
		// keys that would otherwise move focus (Tab, arrows) can still be bound.
		bind.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (!listeningForKey)
				{
					return;
				}
				e.consume();
				listeningForKey = false;
				if (e.getKeyCode() != KeyEvent.VK_ESCAPE)
				{
					configManager.setConfiguration(ExchangeInsightsCaptureConfig.GROUP, "manualToggleHotkey",
						new Keybind(e.getKeyCode(), e.getModifiersEx()));
				}
				refresh();
			}
		});

		// Clicking away without pressing anything should not leave it stuck listening.
		bind.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				if (listeningForKey)
				{
					listeningForKey = false;
					refresh();
				}
			}
		});

		bindRow.add(bind);

		if (listeningForKey)
		{
			bindRow.add(Box.createVerticalStrut(3));
			final JLabel cue = new JLabel("<html>Listening for a key &middot; Escape to cancel</html>");
			cue.setFont(FontManager.getRunescapeSmallFont());
			cue.setForeground(new Color(0xE8, 0xC0, 0x50));
			cue.setAlignmentX(Component.LEFT_ALIGNMENT);
			bindRow.add(cue);
		}
	}

	// ------------------------------------------------------------------
	// Rows
	// ------------------------------------------------------------------

	private void refreshStatus()
	{
		statusRow.removeAll();

		final ClipRecorder recorder = plugin.getRecorder();
		final CaptureMode mode = config.captureMode();

		final int saving = recorder == null ? 0 : recorder.getSavingCount();

		final String text;
		final Color color;
		// Encoding outlasts capture by a long way, so it gets its own state ahead of the rest.
		if (saving > 0)
		{
			text = saving > 1 ? "Saving " + saving + " clips..." : "Saving clip...";
			color = AMBER;
		}
		else if (mode == CaptureMode.OFF)
		{
			text = "Off";
			color = RED;
		}
		else if (mode == CaptureMode.MANUAL)
		{
			if (recorder != null && recorder.isSessionActive())
			{
				final int seconds = recorder.getSessionSeconds();
				text = "Recording  " + formatDuration(seconds);
				color = RED;
			}
			else
			{
				text = "Manual - not armed";
				color = AMBER;
			}
		}
		else if (recorder != null && recorder.isSessionActive())
		{
			text = "Recording";
			color = RED;
		}
		else
		{
			text = "Armed - capturing";
			color = GREEN;
		}

		final JLabel status = new JLabel(text);
		status.setFont(FontManager.getRunescapeBoldFont());
		status.setForeground(Color.WHITE);
		status.setOpaque(false);
		status.setHorizontalAlignment(SwingConstants.CENTER);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		status.setBorder(new RoundedBorder(color.brighter(), new Insets(6, 10, 6, 10)));

		final JPanel wrap = new JPanel(new BorderLayout())
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, color);
				super.paintComponent(g);
			}
		};
		wrap.setOpaque(false);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		wrap.add(status, BorderLayout.CENTER);
		statusRow.add(wrap);
	}

	private void refreshModes()
	{
		modeRow.removeAll();
		for (CaptureMode m : CaptureMode.values())
		{
			final boolean active = config.captureMode() == m;
			final JButton b = styledButton(m.toString(), active ? GREEN : NEUTRAL);
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setToolTipText(describe(m));
			b.addActionListener(e -> setMode(m));
			modeRow.add(b);
		}

	}

	private void refreshAccount()
	{
		accountRow.removeAll();
		accountRow.add(Box.createVerticalStrut(6));

		final String source = uploader.tokenSource();
		final JButton btn;
		if (linking)
		{
			btn = styledButton("Linking... approve it in your browser", AMBER);
			btn.setEnabled(false);
		}
		else if (source != null && uploader.isTokenRejected())
		{
			// A token we hold that the server refuses. Saying "linked" here would be a lie the
			// server disagrees with, so offer to re-link instead.
			btn = styledButton("✗  Token rejected - re-link", RED);
			btn.setToolTipText("The server refused this token (revoked or rotated). Click to link again.");
			btn.addActionListener(e -> plugin.startAccountLink());
		}
		else if (source != null)
		{
			btn = styledButton("✓  Account linked", GREEN);
			btn.setToolTipText("<html>Token supplied by: " + source
				+ ".<br>Click to unlink this character from your Exchange Insights account.</html>");
			btn.addActionListener(e -> plugin.startAccountUnlink());
		}
		else
		{
			btn = styledButton("✗  Link account", RED);
			btn.setToolTipText("<html>One-click: opens exchange-insights.gg to approve linking this "
				+ "character - no token to copy.<br>Or paste an existing token in plugin settings.</html>");
			btn.addActionListener(e -> plugin.startAccountLink());
		}
		btn.setAlignmentX(Component.LEFT_ALIGNMENT);
		btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		accountRow.add(btn);

		if (source == null)
		{
			return;
		}

		// Everything below is the SERVER's answer, rendered verbatim. The plugin holds no copy of
		// the tier limits, so it cannot drift from them and cannot be edited into a bigger allowance.
		final ClipUploader.Quota q = uploader.getLastQuota();
		if (q == null)
		{
			accountRow.add(Box.createVerticalStrut(4));
			final JLabel pending = new JLabel("Checking upload allowance...");
			pending.setFont(FontManager.getRunescapeSmallFont());
			pending.setForeground(MUTED);
			pending.setAlignmentX(Component.LEFT_ALIGNMENT);
			accountRow.add(pending);
			return;
		}

		accountRow.add(Box.createVerticalStrut(10));
		accountRow.add(sectionLabel("Cloud storage"));
		accountRow.add(Box.createVerticalStrut(4));
		final JLabel tier = new JLabel("Account tier: " + (q.isPremium() ? "Premium" : "Free"));
		tier.setFont(FontManager.getRunescapeSmallFont());
		tier.setForeground(q.isPremium() ? new Color(0xE8, 0xC0, 0x50) : MUTED);
		tier.setAlignmentX(Component.LEFT_ALIGNMENT);
		accountRow.add(tier);

		// The account is full and the server has actually refused something. Said here, at the top
		// of the panel, because the alternative is a player noticing weeks later that nothing has
		// reached their account - the upload is a background job with no other visible failure.
		if (uploader.isCloudFull())
		{
			accountRow.add(Box.createVerticalStrut(6));
			accountRow.add(storageFullNotice());
		}

		// One bar, because there is now one limit. The account used to carry a clip-count limit
		// too, and it never bound - space ran out first every time - so the second bar only
		// invited the question of which number actually mattered.
		accountRow.add(Box.createVerticalStrut(3));
		accountRow.add(usageBar(q.used.bytes, q.quota.bytes,
			formatBytes(q.used.bytes) + " / " + formatBytes(q.quota.bytes)));

		accountRow.add(Box.createVerticalStrut(3));
		final JLabel count = new JLabel(q.used.clips == 1 ? "1 clip uploaded"
			: q.used.clips + " clips uploaded");
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(MUTED);
		count.setAlignmentX(Component.LEFT_ALIGNMENT);
		accountRow.add(count);
	}

	/**
	 * The "cloud storage is full" row: what happened, and one click to somewhere it can be fixed.
	 *
	 * <p>Clickable as a whole rather than hiding the action behind a small icon - the point is that
	 * it cannot be missed, and a row that looks like a notice but only responds on one glyph is a
	 * worse version of both.
	 */
	private JPanel storageFullNotice()
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(new Color(0x4A, 0x2A, 0x2A));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		final JLabel text = new JLabel("<html><body style='width:135px'><b>Cloud storage full</b><br>"
			+ "New clips are staying on this computer. Click to manage your clips or change what "
			+ "happens when it fills.</body></html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(Color.WHITE);
		row.add(text, BorderLayout.CENTER);

		final JLabel go = new JLabel(new SyncIcon(SyncIcon.Kind.REVEAL, true));
		go.setToolTipText("Open your clips on exchange-insights.gg");
		row.add(go, BorderLayout.EAST);

		final java.awt.event.MouseAdapter open = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				net.runelite.client.util.LinkBrowser.browse("https://exchange-insights.gg/#clips");
			}
		};
		row.addMouseListener(open);
		text.addMouseListener(open);
		go.addMouseListener(open);
		return row;
	}

	/** A slim fill bar with its numbers on top; amber past 80%, red when full. */
	private static JPanel usageBar(long used, long limit, String text)
	{
		final double frac = limit <= 0 ? 0 : Math.min(1.0, used / (double) limit);
		final Color fill = frac >= 1.0 ? RED : frac >= 0.8 ? AMBER : GREEN;

		final JPanel bar = new JPanel(new BorderLayout())
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, new Color(0x2A, 0x2A, 0x2A));
				final int w = (int) Math.round(getWidth() * frac);
				if (w > 0)
				{
					final Graphics sub = g.create(0, 0, w, getHeight());
					RoundedBorder.fill(sub, this, fill);
					sub.dispose();
				}
				super.paintComponent(g);
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		bar.setPreferredSize(new Dimension(100, 18));

		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		bar.add(label, BorderLayout.CENTER);
		return bar;
	}

	private static String formatBytes(long bytes)
	{
		if (bytes >= 1024L * 1024L * 1024L)
		{
			return String.format("%.1fGB", bytes / (double) (1024L * 1024L * 1024L));
		}
		return String.format("%dMB", Math.round(bytes / (double) (1024L * 1024L)));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void setMode(CaptureMode mode)
	{
		configManager.setConfiguration(ExchangeInsightsCaptureConfig.GROUP, "captureMode", mode);
		refresh();
	}

	private static String describe(CaptureMode m)
	{
		switch (m)
		{
			case OFF:
				return "Capture nothing. No performance cost at all.";
			case MANUAL:
				return "Idle until you press the arm hotkey, then records the whole take. Free while disarmed.";
			default:
				return "Always buffer the last few seconds so triggers can save the lead-up.";
		}
	}

	private static String formatDuration(int seconds)
	{
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	private static JLabel sectionLabel(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(MUTED);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A column that never grows wider than the scroll viewport showing it. */
	private static final class ScrollableColumn extends JPanel implements javax.swing.Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction)
		{
			return r.height;
		}

		/** The whole point: never wider than the viewport. */
		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		/**
		 * Fill the viewport when the content is shorter than it.
		 *
		 * <p>This is what lets the clip list push its bottom pager to the foot of the panel. A
		 * glue only absorbs space its container actually has, and without this the column is
		 * exactly as tall as its contents - so the glue got nothing and the pager rode up under
		 * the last card. Once the content is taller than the viewport this goes back to false and
		 * normal scrolling resumes.
		 */
		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			final java.awt.Container parent = getParent();
			return parent instanceof javax.swing.JViewport
				&& parent.getHeight() > getPreferredSize().height;
		}
	}

	/** Filled, rounded, bold - the same control family the Bank Templates panel uses. */
	private static JButton styledButton(String text, Color bg)
	{
		final JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, getBackground());
				super.paintComponent(g);
			}
		};
		b.setFont(FontManager.getRunescapeBoldFont());
		b.setHorizontalAlignment(SwingConstants.CENTER);
		b.setForeground(Color.WHITE);
		b.setBackground(bg);
		b.setContentAreaFilled(false);
		b.setOpaque(false);
		b.setFocusPainted(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setBorder(new RoundedBorder(bg.brighter(), new Insets(5, 10, 5, 10)));
		return b;
	}
}
