package com.instantreplay;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The saved-clip list: a card per clip with its thumbnail, name and size, and controls to
 * rename, delete or open it.
 *
 * <p>Local files are the source of truth here. Uploaded copies are marked with a cloud
 * glyph rather than listed separately, because from the player's point of view there is one
 * clip that may or may not also live on their account.
 */
@Slf4j
class ClipListPanel extends JPanel
{
	private static final Color CARD_BG = new Color(0x27, 0x27, 0x27);
	private static final Color MUTED = new Color(0x9E, 0x9E, 0x9E);
	private static final Color RED = new Color(94, 44, 44);
	private static final int MAX_SHOWN = 25;

	private final InstantReplayConfig config;
	private final Runnable onChanged;

	ClipListPanel(InstantReplayConfig config, Runnable onChanged)
	{
		this.config = config;
		this.onChanged = onChanged;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	/** Rebuild from disk. Cheap: a directory listing plus already-written thumbnails. */
	void refresh()
	{
		removeAll();

		final List<ClipLibrary.Entry> clips = ClipLibrary.list(config);
		if (clips.isEmpty())
		{
			final JLabel empty = new JLabel("<html>No clips yet.</html>");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(MUTED);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(empty);
			revalidate();
			repaint();
			return;
		}

		int shown = 0;
		for (ClipLibrary.Entry e : clips)
		{
			if (shown++ >= MAX_SHOWN)
			{
				break;
			}
			add(card(e));
			add(Box.createVerticalStrut(6));
		}

		if (clips.size() > MAX_SHOWN)
		{
			final JLabel more = new JLabel("<html>+ " + (clips.size() - MAX_SHOWN) + " older clips</html>");
			more.setFont(FontManager.getRunescapeSmallFont());
			more.setForeground(MUTED);
			more.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(more);
		}

		revalidate();
		repaint();
	}

	/** One clip card, stacked: name and size, then the preview, then its actions. */
	private JPanel card(ClipLibrary.Entry entry)
	{
		final JPanel card = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, CARD_BG);
				super.paintComponent(g);
			}
		};
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setOpaque(false);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7));

		final JLabel name = new JLabel(entry.displayName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		name.setToolTipText(entry.name);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(name);

		final JLabel size = new JLabel(formatBytes(entry.bytes));
		size.setFont(FontManager.getRunescapeSmallFont());
		size.setForeground(MUTED);
		size.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(size);

		card.add(Box.createVerticalStrut(5));

		// Thumbnail, or a neutral placeholder for clips saved before thumbnails existed.
		final Image thumb = ClipLibrary.thumbnail(config, entry);
		final JLabel image = new JLabel();
		image.setAlignmentX(Component.LEFT_ALIGNMENT);
		image.setHorizontalAlignment(SwingConstants.CENTER);
		if (thumb != null)
		{
			image.setIcon(new ImageIcon(thumb));
		}
		else
		{
			image.setText("no preview");
			image.setFont(FontManager.getRunescapeSmallFont());
			image.setForeground(MUTED);
			image.setPreferredSize(new Dimension(ClipLibrary.THUMB_WIDTH, 60));
			image.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
		}
		image.setToolTipText("Click to open this clip");
		image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		image.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				open(entry);
			}
		});
		card.add(image);

		card.add(Box.createVerticalStrut(6));

		final JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		actions.add(smallButton("Rename", e -> rename(entry)));
		actions.add(Box.createHorizontalStrut(5));
		actions.add(smallButton("Delete", e -> delete(entry)));
		actions.add(Box.createHorizontalGlue());
		card.add(actions);

		return card;
	}

	private void open(ClipLibrary.Entry entry)
	{
		// Hand off to the OS player rather than trying to play video in a Swing panel.
		try
		{
			if (Desktop.isDesktopSupported() && entry.file.isFile())
			{
				Desktop.getDesktop().open(entry.file);
			}
		}
		catch (Exception e)
		{
			log.debug("could not open clip {}", entry.name, e);
		}
	}

	private void rename(ClipLibrary.Entry entry)
	{
		final String requested = JOptionPane.showInputDialog(this, "New name for this clip:",
			entry.displayName());
		if (requested == null || requested.trim().isEmpty())
		{
			return;
		}
		if (ClipLibrary.rename(config, entry, requested) == null)
		{
			JOptionPane.showMessageDialog(this,
				"Couldn't rename the clip - a file with that name may already exist, or it is open in another program.",
				"Rename failed", JOptionPane.WARNING_MESSAGE);
		}
		onChanged.run();
	}

	private void delete(ClipLibrary.Entry entry)
	{
		final int choice = JOptionPane.showConfirmDialog(this,
			"Delete " + entry.displayName() + "?\n\nThis removes the local file. Any copy already\n"
				+ "uploaded to your account is not affected.",
			"Delete clip", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}
		if (!ClipLibrary.delete(config, entry))
		{
			JOptionPane.showMessageDialog(this,
				"Couldn't delete the clip - it may be open in another program.",
				"Delete failed", JOptionPane.WARNING_MESSAGE);
		}
		onChanged.run();
	}

	private static JButton smallButton(String text, java.awt.event.ActionListener action)
	{
		final JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(Color.WHITE);
		b.setBackground(text.equals("Delete") ? RED : new Color(60, 60, 60));
		b.setContentAreaFilled(false);
		b.setOpaque(false);
		b.setFocusPainted(false);
		b.setMargin(new java.awt.Insets(1, 4, 1, 4));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setBorder(new RoundedBorder(b.getBackground().brighter(), new java.awt.Insets(2, 6, 2, 6)));
		final JButton self = b;
		b.addActionListener(action);
		b.setUI(new javax.swing.plaf.basic.BasicButtonUI()
		{
			@Override
			public void paint(Graphics g, javax.swing.JComponent c)
			{
				RoundedBorder.fill(g, c, self.getBackground());
				super.paint(g, c);
			}
		});
		return b;
	}

	private static String formatBytes(long bytes)
	{
		if (bytes >= 1024L * 1024L * 1024L)
		{
			return String.format("%.1f GB", bytes / (double) (1024L * 1024L * 1024L));
		}
		if (bytes >= 1024L * 1024L)
		{
			return String.format("%.0f MB", bytes / (double) (1024L * 1024L));
		}
		return Math.max(1, bytes / 1024L) + " KB";
	}
}
