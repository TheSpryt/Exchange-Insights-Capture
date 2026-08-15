package com.exchangeinsightscapture;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
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
	private static final Color AMBER_TEXT = new Color(230, 170, 40);
	/** Clips per page. Matches the Bank Templates panel so the two feel the same. */
	private static final int PAGE_SIZE = 10;
	/** Fetched previews for clips with no local file. Bounded; keyed by the account's clip id. */
	private static final java.util.Map<Long, Image> REMOTE_THUMBS =
		java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long, Image>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(java.util.Map.Entry<Long, Image> eldest)
			{
				return size() > 40;
			}
		});
	/** Usable width inside a card, after the panel border and the card's own padding. */
	static final int PREVIEW_WIDTH = net.runelite.client.ui.PluginPanel.PANEL_WIDTH - 34;

	private final ExchangeInsightsCaptureConfig config;
	private final Runnable onChanged;
	private final java.util.concurrent.ScheduledExecutorService executor;
	/** Clips being produced right now, so the list can hold a slot for each. */
	private final java.util.function.Supplier<java.util.List<ClipRecorder.PendingClip>> pendingClips;
	private final java.util.function.BiConsumer<Long, String> onRenamePending;
	private final java.util.function.LongConsumer onCancelPending;
	private final ClipUploader uploader;
	/** Asks the owning panel to re-read the account's usage figures. */
	private Runnable onQuotaStale = () -> { };

	ClipListPanel(ExchangeInsightsCaptureConfig config, Runnable onChanged,
		java.util.concurrent.ScheduledExecutorService executor,
		java.util.function.Supplier<java.util.List<ClipRecorder.PendingClip>> pendingClips,
		java.util.function.BiConsumer<Long, String> onRenamePending,
		java.util.function.LongConsumer onCancelPending,
		ClipUploader uploader)
	{
		this.config = config;
		this.onChanged = onChanged;
		this.executor = executor;
		this.pendingClips = pendingClips;
		this.onRenamePending = onRenamePending;
		this.onCancelPending = onCancelPending;
		this.uploader = uploader;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	void setQuotaListener(Runnable listener)
	{
		this.onQuotaStale = listener;
	}

	/** The account's clips, refreshed off the EDT so the list never blocks on the network. */
	private volatile java.util.List<ClipUploader.RemoteClip> remote = java.util.Collections.emptyList();

	/**
	 * Re-read the account's clip list AND its usage, then redraw.
	 *
	 * <p>Both together, always: deleting or uploading a clip changes the listing and the bytes
	 * used at the same moment, so refreshing only the listing left the usage bars showing a
	 * figure the server no longer agreed with.
	 */
	void refreshRemote()
	{
		fetchRemote(true);
	}

	/**
	 * The polling path: re-read the server and the folder, but only rebuild if something moved.
	 *
	 * <p>Clips can disappear without this client doing anything - deleted from the website or
	 * another client, or evicted server-side when the account goes over its allowance - and
	 * nothing pushes that to us. So the list is re-read on a timer while the panel is open.
	 *
	 * <p>Rebuilding unconditionally would tear down and recreate every card every tick, which is
	 * visible if you are reading or scrolling the list at the time. Comparing a signature first
	 * means a quiet account costs one request and no UI churn at all.
	 */
	void refreshRemoteIfChanged()
	{
		fetchRemote(false);
	}

	private void fetchRemote(boolean force)
	{
		executor.execute(() ->
		{
			final java.util.List<ClipUploader.RemoteClip> fetched = uploader.listRemote();
			if (fetched == null)
			{
				// The request failed rather than came back empty. Keep showing what we had:
				// blanking the list on a dropped request would make every uploaded clip flicker
				// out and back every time the network hiccuped.
				return;
			}
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				remote = fetched;
				if (!force && signature(visible()).equals(lastSignature))
				{
					return;
				}
				if (!force)
				{
					log.debug("clip list changed elsewhere; refreshing");
				}
				refresh();
				onQuotaStale.run();
			});
		});
	}

	/**
	 * What the list currently shows, as a comparable string: every clip's name, size and remote
	 * id, plus anything mid-encode. Any add, delete, rename or upload changes it.
	 *
	 * <p>Takes the list rather than fetching it. Building it means walking the clips folder, and
	 * this used to do that independently of the caller that had just walked it - so one refresh
	 * cost two or three recursive directory scans, on the EDT, every twenty seconds.
	 */
	private String signature(List<ClipEntry> clips)
	{
		final StringBuilder sb = new StringBuilder();
		for (ClipRecorder.PendingClip p : pendingClips.get())
		{
			sb.append(p.name).append('|');
		}
		for (ClipEntry e : clips)
		{
			sb.append(e.name).append(':')
				.append(e.local == null ? -1 : e.local.length()).append(':')
				.append(e.remoteId).append('|');
		}
		return sb.toString();
	}

	/** Lowercased name filter from the search box; empty shows everything. */
	private String filter = "";
	/** Newest first by default - a replay buffer is about what just happened. */
	private boolean newestFirst = true;
	/** Index of the first clip on the visible page. */
	private int offset;

	void setFilter(String text)
	{
		this.filter = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
		// Back to the first page: the old offset refers to a list that no longer exists, and
		// searching only to land on an empty page three looks broken.
		this.offset = 0;
		refresh();
	}

	void setNewestFirst(boolean value)
	{
		this.newestFirst = value;
		this.offset = 0;
		refresh();
	}

	boolean isNewestFirst()
	{
		return newestFirst;
	}

	/** The clips to show, after the search box and the sort order have had their say. */
	private List<ClipEntry> visible()
	{
		final List<ClipEntry> all = ClipEntry.merge(ClipLibrary.list(config), remote);
		final List<ClipEntry> kept = new java.util.ArrayList<>();
		for (ClipEntry e : all)
		{
			if (filter.isEmpty() || e.name.toLowerCase(java.util.Locale.ROOT).contains(filter))
			{
				kept.add(e);
			}
		}
		if (!newestFirst)
		{
			java.util.Collections.reverse(kept);
		}
		return kept;
	}

	/** The signature as of the last rebuild, so the poll can tell whether anything moved. */
	private String lastSignature = "";

	/** Rebuild from disk. Cheap: a directory listing plus already-written thumbnails. */
	void refresh()
	{
		removeAll();

		// A clip being captured or encoded has no file yet, so it cannot appear in the listing.
		// Hold a slot for it at the top instead: the row appears the instant the trigger fires
		// and is replaced by the real card when the file lands, rather than the list sitting
		// unchanged for the half-minute an encode takes.
		final java.util.List<ClipRecorder.PendingClip> inFlight = pendingClips.get();
		for (ClipRecorder.PendingClip p : inFlight)
		{
			add(pendingCard(p));
			add(Box.createVerticalStrut(6));
		}

		final List<ClipEntry> clips = visible();
		if (inFlight.isEmpty() && clips.isEmpty())
		{
			lastSignature = signature(clips);
			final JLabel empty = new JLabel(filter.isEmpty()
				? "<html>No clips yet.</html>"
				: "<html>No clips match that search.</html>");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(MUTED);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(empty);
			add(Box.createVerticalGlue());
			add(paginationRow(0));
			revalidate();
			repaint();
			return;
		}

		// Deleting the last clip on a page would otherwise strand the view past the end of the
		// list, showing nothing with no obvious way back.
		if (offset >= clips.size())
		{
			offset = Math.max(0, ((clips.size() - 1) / PAGE_SIZE) * PAGE_SIZE);
		}
		final int end = Math.min(offset + PAGE_SIZE, clips.size());

		// A pager at both ends, always - a full page is taller than the panel, so paging from the
		// bottom without one means scrolling back up every time. Shown even when everything fits,
		// because a control that appears and disappears as the list crosses ten items reads as a
		// glitch, and the range doubles as the clip count.
		add(paginationRow(clips.size()));
		add(Box.createVerticalStrut(6));

		for (int i = offset; i < end; i++)
		{
			add(card(clips.get(i)));
			add(Box.createVerticalStrut(6));
		}

		// Pin the bottom pager to the foot of the panel when the page does not fill it, so it
		// stays put between pages instead of riding up under the last card. Absorbs nothing once
		// the content is taller than the viewport.
		add(Box.createVerticalGlue());
		add(paginationRow(clips.size()));

		lastSignature = signature(clips);
		revalidate();
		repaint();
	}

	/** {@code «  <  1-10 of 34  >  »} - the range carries the count, so no separate total row. */
	private JPanel paginationRow(int total)
	{
		final JPanel nav = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 4, 2));
		nav.setBackground(ColorScheme.DARK_GRAY_COLOR);
		nav.setAlignmentX(Component.LEFT_ALIGNMENT);
		nav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		final int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
		final int page = offset / PAGE_SIZE;
		final boolean hasPrev = offset > 0;
		final boolean hasNext = offset + PAGE_SIZE < total;
		final int from = total > 0 ? offset + 1 : 0;

		nav.add(pagerButton("«", "First page", hasPrev, () -> goTo(0)));
		nav.add(pagerButton("<", "Previous page", hasPrev, () -> goTo(offset - PAGE_SIZE)));

		final JLabel range = new JLabel(total > 0
			? from + "-" + Math.min(offset + PAGE_SIZE, total) + " of " + total
			: "0 of 0");
		range.setFont(FontManager.getRunescapeSmallFont());
		range.setForeground(MUTED);
		range.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		nav.add(range);

		nav.add(pagerButton(">", "Next page", hasNext, () -> goTo(offset + PAGE_SIZE)));
		nav.add(pagerButton("»", "Last page", page < totalPages - 1,
			() -> goTo((totalPages - 1) * PAGE_SIZE)));
		return nav;
	}

	private void goTo(int newOffset)
	{
		offset = Math.max(0, newOffset);
		refresh();
	}

	/** A bare arrow rather than a button: white and clickable, or greyed out at the ends. */
	private static JLabel pagerButton(String text, String tooltip, boolean enabled, Runnable action)
	{
		final JLabel b = new JLabel(text);
		b.setForeground(enabled ? Color.WHITE : new Color(0x66, 0x66, 0x66));
		b.setHorizontalAlignment(SwingConstants.CENTER);
		b.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
		if (enabled)
		{
			b.setToolTipText(tooltip);
			b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			b.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					action.run();
				}
			});
		}
		return b;
	}

	/**
	 * The card shown while a clip is still being captured or encoded. It carries the clip's
	 * real name, so renaming it here decides what the file is called when it lands - no need
	 * to wait half a minute and rename it afterwards.
	 */
	private JPanel pendingCard(ClipRecorder.PendingClip clip)
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

		final JLabel name = new JLabel(clip.name);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		name.setToolTipText(clip.name + ".mp4");
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		name.setMaximumSize(new Dimension(PREVIEW_WIDTH, 16));
		name.setPreferredSize(new Dimension(PREVIEW_WIDTH, 16));
		card.add(name);

		final JLabel note = new JLabel(clip.encoding ? "encoding..." : "capturing...");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(AMBER_TEXT);
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(note);

		card.add(Box.createVerticalStrut(5));

		// A black frame the same shape a real preview will be, so the card does not jump size
		// when the clip finishes and its own frame replaces this.
		final JPanel blank = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, java.awt.Color.BLACK);
				super.paintComponent(g);
			}
		};
		blank.setOpaque(false);
		blank.setAlignmentX(Component.LEFT_ALIGNMENT);
		final int h = Math.round(PREVIEW_WIDTH * 9f / 16f);
		blank.setPreferredSize(new Dimension(PREVIEW_WIDTH, h));
		blank.setMaximumSize(new Dimension(PREVIEW_WIDTH, h));
		card.add(blank);

		card.add(Box.createVerticalStrut(6));

		final JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		actions.add(smallButton("Rename", e -> renamePending(clip)));
		actions.add(Box.createHorizontalStrut(5));
		actions.add(smallButton("Cancel", e -> cancelPending(clip)));
		actions.add(Box.createHorizontalGlue());
		card.add(actions);

		return card;
	}

	private void renamePending(ClipRecorder.PendingClip clip)
	{
		final String requested = JOptionPane.showInputDialog(this, "Name for this clip:", clip.name);
		if (requested == null)
		{
			return;
		}
		final String safe = ClipStorage.safeName(requested);
		if (safe.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "That name cannot be used for a file.",
				"Rename clip", JOptionPane.WARNING_MESSAGE);
			return;
		}
		onRenamePending.accept(clip.id, safe);
		onChanged.run();
	}

	private void cancelPending(ClipRecorder.PendingClip clip)
	{
		final int choice = JOptionPane.showConfirmDialog(this,
			"Cancel this clip?\n\nIt will not be saved.",
			"Cancel clip", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}
		onCancelPending.accept(clip.id);
		onChanged.run();
	}

	/**
	 * One clip row. A clip appears here once whether it is on disk, on the account or both;
	 * the icons say which, so the list is a view of "my clips" rather than of two stores.
	 */
	private JPanel card(ClipEntry entry)
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

		final JLabel name = new JLabel(entry.name);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		name.setToolTipText(entry.name + ".mp4");
		name.setMaximumSize(new Dimension(PREVIEW_WIDTH, 16));
		name.setPreferredSize(new Dimension(PREVIEW_WIDTH, 16));
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(name);

		// Size, then the two state icons: where this clip lives, and whether it can be pulled down.
		final JPanel meta = new JPanel();
		meta.setLayout(new BoxLayout(meta, BoxLayout.X_AXIS));
		meta.setOpaque(false);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		meta.setMaximumSize(new Dimension(PREVIEW_WIDTH, 16));

		// Length beside size. Read from the container off the EDT, then filled in - the capture
		// rate varies now, so nothing outside the file knows how long a clip actually runs.
		final JLabel size = new JLabel(formatBytes(entry.bytes));
		size.setFont(FontManager.getRunescapeSmallFont());
		size.setForeground(MUTED);
		meta.add(size);

		if (entry.isLocal())
		{
			final ClipLibrary.Entry file = new ClipLibrary.Entry(entry.local);
			final Double known = ClipLibrary.cachedDuration(file);
			final JLabel length = new JLabel(known == null ? "" : "  ·  " + formatDuration(known));
			length.setFont(FontManager.getRunescapeSmallFont());
			length.setForeground(MUTED);
			meta.add(length);
			if (known == null)
			{
				executor.execute(() ->
				{
					final Double read = ClipLibrary.readDuration(file);
					if (read != null)
					{
						javax.swing.SwingUtilities.invokeLater(() ->
						{
							length.setText("  ·  " + formatDuration(read));
							revalidate();
							repaint();
						});
					}
				});
			}
		}

		meta.add(Box.createHorizontalGlue());

		// Only for clips that exist on this machine - there is nothing to show in a file
		// manager for one that lives only on the account.
		if (entry.isLocal())
		{
			final JLabel show = new JLabel(new SyncIcon(SyncIcon.Kind.REVEAL, true));
			show.setToolTipText("Show this clip in your file manager");
			show.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			show.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					FileReveal.reveal(entry.local);
				}
			});
			meta.add(show);
			meta.add(Box.createHorizontalStrut(5));
		}

		// Uploaded -> cloud. Local-only -> an upload arrow, which becomes a cloud once the
		// account listing confirms the upload rather than optimistically on send.
		final JLabel cloud = new JLabel(entry.isUploaded()
			? new SyncIcon(SyncIcon.Kind.CLOUD, true)
			: new SyncIcon(SyncIcon.Kind.UPLOAD, true));
		cloud.setToolTipText(entry.isUploaded()
			? "Saved to your Exchange Insights account"
			: "On this computer only - click to upload");
		if (!entry.isUploaded())
		{
			cloud.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			cloud.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					uploadNow(entry);
				}
			});
		}
		meta.add(cloud);
		meta.add(Box.createHorizontalStrut(5));

		// Downloadable only when the account has it and this machine does not.
		final boolean canDownload = entry.isUploaded() && !entry.isLocal();
		final JLabel download = new JLabel(new SyncIcon(SyncIcon.Kind.DOWNLOAD, canDownload));
		download.setToolTipText(canDownload
			? "Download this clip to this computer"
			: entry.isLocal() ? "Already on this computer" : "Not available to download");
		if (canDownload)
		{
			download.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			download.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					downloadNow(entry);
				}
			});
		}
		meta.add(download);
		card.add(meta);

		card.add(Box.createVerticalStrut(5));
		card.add(preview(entry));

		// A transfer in flight gets a bar under the preview. Absent otherwise, so the card stays
		// quiet the rest of the time. Only one direction can be running for a given clip.
		final Float uploading = entry.isLocal() ? uploader.uploadProgress(entry.local.getName()) : null;
		final Float downloading = uploader.downloadProgress(entry.name + ".mp4");
		if (uploading != null)
		{
			card.add(Box.createVerticalStrut(4));
			card.add(transferBar(uploading, "uploading"));
		}
		else if (downloading != null)
		{
			card.add(Box.createVerticalStrut(4));
			card.add(transferBar(downloading, "downloading"));
		}

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

	/** The preview image, or a placeholder for a clip with no local file to decode. */
	private JLabel preview(ClipEntry entry)
	{
		final JLabel image = new JLabel();
		image.setAlignmentX(Component.LEFT_ALIGNMENT);
		image.setHorizontalAlignment(SwingConstants.CENTER);

		if (!entry.isLocal())
		{
			// No file to decode, so use the preview the uploading client stored - which is the
			// same image this client would have produced, because it came from the same code.
			final Image cachedRemote = REMOTE_THUMBS.get(entry.remoteId);
			if (cachedRemote != null)
			{
				image.setIcon(new ImageIcon(scaleToWidth(cachedRemote)));
				return image;
			}
			image.setText("loading preview...");
			image.setFont(FontManager.getRunescapeSmallFont());
			image.setForeground(MUTED);
			image.setPreferredSize(new Dimension(PREVIEW_WIDTH, 50));
			image.setMaximumSize(new Dimension(PREVIEW_WIDTH, 50));
			final Long id = entry.remoteId;
			executor.execute(() ->
			{
				final Image fetched = uploader.fetchThumb(id);
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					if (fetched == null)
					{
						image.setText("no preview");
						return;
					}
					REMOTE_THUMBS.put(id, fetched);
					image.setText(null);
					image.setPreferredSize(null);
					image.setMaximumSize(null);
					image.setIcon(new ImageIcon(scaleToWidth(fetched)));
					revalidate();
					repaint();
				});
			});
			return image;
		}

		final ClipLibrary.Entry file = new ClipLibrary.Entry(entry.local);
		final Image cached = ClipLibrary.cachedPreview(file);
		if (cached != null)
		{
			image.setIcon(new ImageIcon(scaleToWidth(cached)));
		}
		else
		{
			image.setText("loading preview...");
			image.setFont(FontManager.getRunescapeSmallFont());
			image.setForeground(MUTED);
			image.setPreferredSize(new Dimension(PREVIEW_WIDTH, 50));
			image.setMaximumSize(new Dimension(PREVIEW_WIDTH, 50));
			executor.execute(() ->
			{
				final Image decoded = ClipLibrary.decodePreview(file);
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					if (decoded == null)
					{
						image.setText("no preview");
						return;
					}
					image.setText(null);
					image.setPreferredSize(null);
					image.setMaximumSize(null);
					image.setIcon(new ImageIcon(scaleToWidth(decoded)));
					revalidate();
					repaint();
				});
			});
		}
		image.setToolTipText("Click to open this clip");
		image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		image.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				open(entry.local);
			}
		});
		return image;
	}

	private void uploadNow(ClipEntry entry)
	{
		if (entry.local == null)
		{
			return;
		}
		executor.execute(() ->
		{
			uploader.maybeUpload(entry.local, false);
			javax.swing.SwingUtilities.invokeLater(this::refreshRemote);
		});
	}

	private void downloadNow(ClipEntry entry)
	{
		if (entry.remoteId == null)
		{
			return;
		}
		// The name came from the server, so it is not this client's to trust with a file path.
		// The server does sanitise it, but a name that is merely safe in a database row is not the
		// same as one that is safe to create on this filesystem.
		final String name = ClipStorage.safeName(entry.name);
		if (name.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "That clip's name cannot be used as a file here.",
				"Download clip", JOptionPane.WARNING_MESSAGE);
			return;
		}
		final java.io.File target = new java.io.File(ClipStorage.outputDir(config), name + ".mp4");
		executor.execute(() ->
		{
			final boolean ok = uploader.downloadRemote(entry.remoteId, target);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (!ok)
				{
					JOptionPane.showMessageDialog(this, "Couldn't download that clip.",
						"Download failed", JOptionPane.WARNING_MESSAGE);
				}
				onChanged.run();
			});
		});
	}

	private void rename(ClipEntry entry)
	{
		final String requested = JOptionPane.showInputDialog(this, "New name for this clip:", entry.name);
		if (requested == null)
		{
			return;
		}
		final String clean = ClipStorage.safeName(requested);
		if (clean.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "That name cannot be used for a file.",
				"Rename clip", JOptionPane.WARNING_MESSAGE);
			return;
		}

		if (entry.isLocal())
		{
			final ClipLibrary.Entry file = new ClipLibrary.Entry(entry.local);
			if (ClipLibrary.rename(config, file, clean) == null)
			{
				JOptionPane.showMessageDialog(this,
					"Couldn't rename the clip - a file with that name may already exist, or it is open in another program.",
					"Rename failed", JOptionPane.WARNING_MESSAGE);
				return;
			}
		}
		// Rename BOTH copies, so the two do not drift apart into separate rows.
		if (entry.isUploaded())
		{
			uploader.renameRemote(entry.remoteId, clean + ".mp4",
				() -> javax.swing.SwingUtilities.invokeLater(this::refreshRemote));
		}
		onChanged.run();
	}

	private void delete(ClipEntry entry)
	{
		final String message;
		if (entry.isSynced())
		{
			message = "Delete " + entry.name + "?\n\n"
				+ "This deletes BOTH the local file AND the copy on your\n"
				+ "Exchange Insights account. Neither can be recovered.";
		}
		else if (entry.isUploaded())
		{
			message = "Delete " + entry.name + " from your Exchange Insights account?\n\n"
				+ "This cannot be recovered.";
		}
		else
		{
			message = "Delete " + entry.name + "?\n\nThis removes the local file.";
		}

		if (JOptionPane.showConfirmDialog(this, message, "Delete clip",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}

		if (entry.isLocal() && !ClipLibrary.delete(config, new ClipLibrary.Entry(entry.local)))
		{
			JOptionPane.showMessageDialog(this,
				"Couldn't delete the local file - it may be open in another program.",
				"Delete failed", JOptionPane.WARNING_MESSAGE);
		}
		if (entry.isUploaded())
		{
			uploader.deleteRemote(entry.remoteId,
				() -> javax.swing.SwingUtilities.invokeLater(this::refreshRemote),
				err -> javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
					"Couldn't delete the uploaded copy: " + err, "Delete failed", JOptionPane.WARNING_MESSAGE)));
		}
		onChanged.run();
	}

	private void open(java.io.File file)
	{
		// Hand off to the OS player rather than trying to play video in a Swing panel.
		try
		{
			if (file != null && file.isFile())
			{
				// RuneLite's opener, not Desktop: it already handles the per-platform differences,
				// and Desktop is simply absent on a lot of Linux desktops.
				net.runelite.client.util.LinkBrowser.open(file.getAbsolutePath());
			}
		}
		catch (Exception e)
		{
			log.debug("could not open clip {}", file, e);
		}
	}



	private static JButton smallButton(String text, java.awt.event.ActionListener action)
	{
		final JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(Color.WHITE);
		b.setBackground(text.equals("Delete") || text.equals("Cancel") ? RED : new Color(60, 60, 60));
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

	/** A slim fill bar showing how far a transfer has got, in either direction. */
	private static JPanel transferBar(float fraction, String verb)
	{
		final float clamped = Math.max(0f, Math.min(1f, fraction));
		final JPanel bar = new JPanel(new java.awt.BorderLayout())
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, new Color(0x2A, 0x2A, 0x2A));
				final int w = Math.round(getWidth() * clamped);
				if (w > 0)
				{
					final Graphics sub = g.create(0, 0, w, getHeight());
					RoundedBorder.fill(sub, this, new Color(0x7E, 0xC8, 0xE3));
					sub.dispose();
				}
				super.paintComponent(g);
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
		bar.setPreferredSize(new Dimension(PREVIEW_WIDTH, 14));

		final JLabel label = new JLabel(verb + " " + Math.round(clamped * 100) + "%");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		bar.add(label, java.awt.BorderLayout.CENTER);
		return bar;
	}

	/** Scale a decoded frame to the card width, preserving aspect ratio. */
	private static Image scaleToWidth(Image src)
	{
		final int w = src.getWidth(null);
		final int h = src.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			return src;
		}
		final int target = PREVIEW_WIDTH;
		return src.getScaledInstance(target, Math.max(1, Math.round((float) h * target / w)),
			Image.SCALE_SMOOTH);
	}

	/** m:ss, or "0:07" style for short clips - the form a video length is normally read in. */
	private static String formatDuration(double seconds)
	{
		final int total = (int) Math.round(seconds);
		return String.format("%d:%02d", total / 60, total % 60);
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
