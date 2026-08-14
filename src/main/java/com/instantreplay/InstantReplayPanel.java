package com.instantreplay;

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
class InstantReplayPanel extends PluginPanel
{
	private static final Color GREEN = new Color(35, 78, 42);
	private static final Color RED = new Color(94, 44, 44);
	private static final Color AMBER = new Color(96, 74, 30);
	private static final Color NEUTRAL = new Color(60, 60, 60);
	private static final Color MUTED = new Color(0x9E, 0x9E, 0x9E);

	private final InstantReplayPlugin plugin;
	private final InstantReplayConfig config;
	private final ConfigManager configManager;
	private final ClipUploader uploader;

	private final JPanel statusRow = new JPanel();
	private final JPanel modeRow = new JPanel(new GridLayout(1, 3, 4, 0));
	private final JPanel bindRow = new JPanel();
	private final JPanel accountRow = new JPanel();
	private final JLabel hint = new JLabel();
	private final ClipListPanel clipList;

	/** True while the bind button is armed and swallowing the next keypress. */
	private boolean listeningForKey;
	/** True while a browser device-link is in flight. */
	private boolean linking;

	InstantReplayPanel(InstantReplayPlugin plugin, InstantReplayConfig config, ConfigManager configManager,
		ClipUploader uploader)
	{
		// wrap = false: this panel manages its own scrolling, so the settings button can be
		// anchored in SOUTH and stay visible however long the clip list grows.
		super(false);
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.uploader = uploader;
		this.clipList = new ClipListPanel(config, this::refreshClips);

		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(title("Instant Replay"));
		body.add(Box.createVerticalStrut(8));

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

		body.add(Box.createVerticalStrut(6));
		bindRow.setLayout(new BoxLayout(bindRow, BoxLayout.Y_AXIS));
		bindRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bindRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(bindRow);

		body.add(Box.createVerticalStrut(6));
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(MUTED);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(hint);

		body.add(Box.createVerticalStrut(14));
		body.add(sectionLabel("Clips"));
		body.add(Box.createVerticalStrut(4));
		clipList.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(clipList);

		final JPanel scrollHost = new JPanel(new BorderLayout());
		scrollHost.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollHost.add(body, BorderLayout.NORTH);

		final javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(scrollHost,
			javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scroll, BorderLayout.CENTER);

		// Anchored footer: always reachable no matter how many clips are listed.
		final JPanel footer = new JPanel(new BorderLayout());
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		final JButton settings = styledButton("Open plugin settings", NEUTRAL);
		settings.setToolTipText("Opens this plugin's configuration page.");
		settings.setPreferredSize(new Dimension(10, 28));
		settings.addActionListener(e -> plugin.openConfigPanel());
		footer.add(settings, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);

		refresh();
		refreshClips();
		refreshQuota();
	}

	/** Called by the plugin as the link flow progresses, so the button reflects it. */
	void setLinking(boolean value)
	{
		linking = value;
		refresh();
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
					configManager.setConfiguration(InstantReplayConfig.GROUP, "manualToggleHotkey",
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

		final int saving = recorder == null ? 0 : recorder.getPendingEncodes();

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
				final int seconds = recorder.getSessionFrameCount() / Math.max(1, config.framerate());
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
			text = "Armed - buffering";
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

		final CaptureMode mode = config.captureMode();
		if (mode == CaptureMode.MANUAL)
		{
			hint.setText("<html>Nothing is captured until you arm a take.</html>");
		}
		else if (mode == CaptureMode.AUTO)
		{
			hint.setText("<html>Buffering continuously. Lower the framerate if your FPS drops.</html>");
		}
		else
		{
			hint.setText("<html>Nothing is being captured.</html>");
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

		accountRow.add(Box.createVerticalStrut(6));
		final JLabel tier = new JLabel(q.isPremium() ? "Premium" : "Free");
		tier.setFont(FontManager.getRunescapeSmallFont());
		tier.setForeground(q.isPremium() ? new Color(0xE8, 0xC0, 0x50) : MUTED);
		tier.setAlignmentX(Component.LEFT_ALIGNMENT);
		accountRow.add(tier);

		accountRow.add(Box.createVerticalStrut(3));
		accountRow.add(usageBar(q.used.clips, q.quota.clips,
			q.used.clips + " / " + q.quota.clips + " clips"));
		accountRow.add(Box.createVerticalStrut(3));
		accountRow.add(usageBar(q.used.bytes, q.quota.bytes,
			formatBytes(q.used.bytes) + " / " + formatBytes(q.quota.bytes)));

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
		configManager.setConfiguration(InstantReplayConfig.GROUP, "captureMode", mode);
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

	private static JLabel title(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel sectionLabel(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(MUTED);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
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
