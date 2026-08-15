package com.exchangeinsightscapture;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Exchange Insights Capture",
	description = "Automatically saves a video clip of the moments around in-game events like deaths and collection log unlocks",
	tags = {"record", "recording", "video", "clip", "replay", "death", "collection", "capture", "highlight"}
)
public class ExchangeInsightsCapturePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private ExchangeInsightsCaptureConfig config;

	private ClipRecorder recorder;
	private ExchangeInsightsCaptureOverlay overlay;
	private ExchangeInsightsCapturePanel panel;
	private NavigationButton navButton;
	private ClipUploader uploader;
	private volatile long lastSavedAtMs = Long.MIN_VALUE;
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);

	/**
	 * False until the character's real levels have been read after a login.
	 *
	 * <p>Without it, logging in looks like every skill levelling up at once, because the client
	 * reports each one from zero as it fills them in.
	 */
	private boolean levelsReady;

	private final HotkeyListener manualHotkey = new HotkeyListener(() -> config.manualHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (recorder != null && config.captureMode() == CaptureMode.AUTO)
			{
				triggerClip(ClipTrigger.MANUAL, "");
			}
		}
	};

	private final HotkeyListener manualToggleHotkey = new HotkeyListener(() -> config.manualToggleHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			toggleManualSession();
		}
	};

	@Provides
	ExchangeInsightsCaptureConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExchangeInsightsCaptureConfig.class);
	}

	@Override
	protected void startUp()
	{
		uploader = new ClipUploader(config, configManager, httpClient, executor, this::notifyChat);
		recorder = new ClipRecorder(config, drawManager, this::canCapture, this::mousePosition,
			this::onClipSaved, this::onClipError);
		recorder.setCanvasBounds(this::canvasBoundsOnScreen);
		recorder.setPendingListener(this::panelRefreshClips);
		// Old clips were previewed at 190px; re-send them now the generator makes 1280px ones.
		uploader.backfillThumbnails();
		recorder.setUploadHandler((file, temp) ->
		{
			final ClipUploader u = uploader;
			if (u != null)
			{
				u.maybeUpload(file, Boolean.TRUE.equals(temp));
			}
		});
		recorder.start();

		// Pass `this` so overlay.getPlugin() resolves; RuneLite's ConfigPlugin needs it
		// to know which plugin's settings to open (see openConfigPanel).
		overlay = new ExchangeInsightsCaptureOverlay(this, config, this::canCapture,
			() -> recorder != null && recorder.isSessionActive(),
			() -> recorder == null ? 0 : recorder.getSavingCount());
		overlayManager.add(overlay);

		panel = new ExchangeInsightsCapturePanel(this, config, configManager, uploader, executor);
		final BufferedImage icon = ImageUtil.loadImageResource(ExchangeInsightsCapturePlugin.class, "/com/exchangeinsightscapture/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Exchange Insights Capture")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Show the real save path rather than an empty box. A @ConfigItem default has to be a
		// compile-time constant, so the actual folder (which depends on RUNELITE_DIR) can only
		// be filled in at runtime - otherwise the setting reads blank and nobody can tell where
		// their clips went without checking the docs.
		final String configured = config.outputDirectory();
		if (configured == null || configured.trim().isEmpty())
		{
			configManager.setConfiguration(ExchangeInsightsCaptureConfig.GROUP, "outputDirectory",
				ClipStorage.outputDir(config).getAbsolutePath());
		}

		keyManager.registerKeyListener(manualHotkey);
		keyManager.registerKeyListener(manualToggleHotkey);

		// Read the character now as well as on login. Enabling the plugin - or restarting the
		// client into an auto-login - produces no LOGGED_IN event to react to, so waiting for one
		// left the recorder with no account and its clips filed in the root of the save folder.
		clientThread.invokeLater(() ->
		{
			snapshotLevels();
			syncAccountFolder();
		});
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(manualHotkey);
		keyManager.unregisterKeyListener(manualToggleHotkey);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (recorder != null)
		{
			recorder.stop();
			recorder = null;
		}
		uploader = null;
		levels.clear();
	}

	/**
	 * Opens this plugin's own settings page. RuneLite's ConfigPlugin listens for an
	 * OverlayMenuClicked carrying RUNELITE_OVERLAY_CONFIG, resolves the owning plugin
	 * from the overlay, and opens its config panel - so posting that event is the
	 * supported way to get there from our own UI.
	 */
	void openConfigPanel()
	{
		if (overlay == null)
		{
			return;
		}
		eventBus.post(new OverlayMenuClicked(
			new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Exchange Insights Capture"),
			overlay));

		// Land at the top of the settings, not halfway down them.
		//
		// The config page is built fresh each time and never scrolls itself, but something in its
		// layout - a focusable field being made visible - leaves it partway into the trigger list,
		// with the whole Recording section above the fold. Two invokeLaters, because the panel does
		// not exist when the event is posted and is not laid out on the tick it is built.
		javax.swing.SwingUtilities.invokeLater(() ->
			javax.swing.SwingUtilities.invokeLater(this::scrollConfigToTop));
	}

	/**
	 * Put RuneLite's config page back to the top.
	 *
	 * <p>Found by shape rather than by API, because none is offered: the visible scroll pane whose
	 * contents come from RuneLite's config package. Deliberately silent if it finds nothing - this
	 * is a cosmetic nicety, and a client update that moves those classes should cost a slightly
	 * awkward scroll position rather than an exception every time the button is pressed.
	 */
	private void scrollConfigToTop()
	{
		try
		{
			for (java.awt.Window window : java.awt.Window.getWindows())
			{
				if (window.isShowing() && scrollToTopIn(window))
				{
					return;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("could not reset the config scroll position", e);
		}
	}

	private boolean scrollToTopIn(java.awt.Container root)
	{
		for (java.awt.Component child : root.getComponents())
		{
			if (child instanceof javax.swing.JScrollPane && child.isShowing()
				&& holdsConfigPage((javax.swing.JScrollPane) child))
			{
				((javax.swing.JScrollPane) child).getVerticalScrollBar().setValue(0);
				return true;
			}
			if (child instanceof java.awt.Container && scrollToTopIn((java.awt.Container) child))
			{
				return true;
			}
		}
		return false;
	}

	/** True when anything inside this scroll pane belongs to RuneLite's config UI. */
	private boolean holdsConfigPage(javax.swing.JScrollPane scroll)
	{
		final java.awt.Component view = scroll.getViewport().getView();
		return view instanceof java.awt.Container && fromConfigPackage((java.awt.Container) view, 0);
	}

	private boolean fromConfigPackage(java.awt.Container container, int depth)
	{
		if (container.getClass().getName().startsWith("net.runelite.client.plugins.config."))
		{
			return true;
		}
		if (depth > 4)
		{
			return false;
		}
		for (java.awt.Component child : container.getComponents())
		{
			if (child.getClass().getName().startsWith("net.runelite.client.plugins.config."))
			{
				return true;
			}
			if (child instanceof java.awt.Container
				&& fromConfigPackage((java.awt.Container) child, depth + 1))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Start a clip and tell the side panel immediately, so it can hold a slot for the clip
	 * while it is captured and encoded. Without this the list would sit unchanged for the
	 * half-minute an encode takes and only update once the file existed.
	 */
	private void triggerClip(ClipTrigger trigger, String subject)
	{
		final ClipRecorder r = recorder;
		if (r == null)
		{
			return;
		}
		r.trigger(trigger, subject);
		panelRefreshClips();
	}

	/** Push the current character's folder to the recorder. Must run on the client thread. */
	private void syncAccountFolder()
	{
		final ClipRecorder r = recorder;
		if (r != null)
		{
			r.setAccountFolder(accountFolder());
		}
	}

	/**
	 * The folder this character's clips belong in, e.g. "Spryt" or "Spryt-Demonic Pacts League".
	 *
	 * <p>Exactly how RuneLite names its screenshot folders, so the two sit side by side and a
	 * league character never mixes in with the main account. Must be read on the client thread.
	 */
	private String accountFolder()
	{
		final net.runelite.api.Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return null;
		}
		final net.runelite.client.config.RuneScapeProfileType profile =
			net.runelite.client.config.RuneScapeProfileType.getCurrent(client);
		return profile == net.runelite.client.config.RuneScapeProfileType.STANDARD
			? local.getName()
			: local.getName() + "-" + net.runelite.client.util.Text.titleCase(profile);
	}

	/** Current recorder state for the side panel, or null before startUp completes. */
	ClipRecorder getRecorder()
	{
		return recorder;
	}

	// ------------------------------------------------------------------
	// Account linking (same device-link flow as the Bank Templates plugin)
	// ------------------------------------------------------------------

	private static final long LINK_WINDOW_MS = 5 * 60 * 1000L;
	private volatile boolean linking;

	/**
	 * Link this character to an Exchange Insights account. If a token already exists (ours or a
	 * sibling plugin's) the character is attached directly; otherwise the browser device-link
	 * runs: the site is opened, the user approves, and we poll until a token is issued.
	 */
	void startAccountLink()
	{
		if (linking || uploader == null)
		{
			return;
		}

		// Say what linking actually does before doing any of it. This is the first moment the
		// plugin would contact a server outside RuneLite, so the disclosure belongs here rather
		// than buried in a setting the user has already skipped past. Declining contacts nobody.
		final int consent = javax.swing.JOptionPane.showConfirmDialog(panel,
			"<html><body style='width:280px'>Linking opens exchange-insights.gg so you can approve "
				+ "this character."
				+ "<br><br>This submits your IP address to a 3rd-party server not controlled or "
				+ "verified by RuneLite developers. If you also turn on clip uploads, your saved "
				+ "clips are sent there too."
				+ "<br><br>Link this character now?</body></html>",
			"Link Exchange Insights account", javax.swing.JOptionPane.OK_CANCEL_OPTION,
			javax.swing.JOptionPane.QUESTION_MESSAGE);
		if (consent != javax.swing.JOptionPane.OK_OPTION)
		{
			return;
		}

		// Claim the flag on the EDT before the client-thread round trip, so a double click
		// cannot start two flows and open two browser tabs.
		linking = true;
		setPanelLinking(true);

		clientThread.invokeLater(() ->
		{
			final long hash = client.getAccountHash();
			final net.runelite.api.Player local = client.getLocalPlayer();
			final String rsn = local != null ? local.getName() : null;
			javax.swing.SwingUtilities.invokeLater(() -> beginLink(hash, rsn));
		});
	}

	private void beginLink(long accountHash, String rsn)
	{
		if (accountHash == -1 || rsn == null || rsn.isEmpty())
		{
			finishLinking();
			javax.swing.JOptionPane.showMessageDialog(panel,
				"Log into OSRS first (so the plugin knows which character to link), then try again.",
				"Not logged in", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		// ALWAYS prefer a token this client already holds - our own, or one belonging to the
		// Exchange Insights or Bank Templates plugin. Minting a second token for an account
		// that already has one just clutters the account page and gives the user another
		// credential to manage. Only if the existing token is actually refused do we fall
		// back to the browser flow, and only then is a new one issued.
		final String existing = uploader.effectiveToken();
		if (existing != null)
		{
			uploader.linkIdentity(existing, accountHash, rsn,
				() -> javax.swing.SwingUtilities.invokeLater(() -> completeLink(null)),
				error -> javax.swing.SwingUtilities.invokeLater(() -> deviceLink(accountHash, rsn)));
			return;
		}

		deviceLink(accountHash, rsn);
	}

	/** Browser device-link. Only reached when this client holds no usable token at all. */
	private void deviceLink(long accountHash, String rsn)
	{
		uploader.startDeviceLink(accountHash, rsn,
			start -> javax.swing.SwingUtilities.invokeLater(() -> onLinkStarted(start)),
			error -> javax.swing.SwingUtilities.invokeLater(() -> failLink(error)));
	}

	private void onLinkStarted(ClipUploader.LinkStart start)
	{
		if (!linking || start == null || start.verificationUrl == null)
		{
			failLink("the server did not return a link URL");
			return;
		}
		net.runelite.client.util.LinkBrowser.browse(start.verificationUrl);
		final long deadline = System.currentTimeMillis() + LINK_WINDOW_MS;
		final long intervalMs = Math.max(2, start.pollSeconds) * 1000L;
		schedulePoll(start.deviceSecret, deadline, intervalMs);
	}

	private void schedulePoll(String secret, long deadline, long intervalMs)
	{
		executor.schedule(() ->
		{
			if (!linking)
			{
				return;
			}
			if (System.currentTimeMillis() > deadline)
			{
				javax.swing.SwingUtilities.invokeLater(() -> failLink("the link request timed out"));
				return;
			}
			uploader.pollDeviceLink(secret,
				poll -> javax.swing.SwingUtilities.invokeLater(() -> handlePoll(poll, secret, deadline, intervalMs)),
				// Transient failure: keep trying until the deadline rather than giving up.
				err -> schedulePoll(secret, deadline, intervalMs));
		}, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private void handlePoll(ClipUploader.LinkPoll poll, String secret, long deadline, long intervalMs)
	{
		if (!linking)
		{
			return;
		}
		final String status = poll != null && poll.status != null ? poll.status : "";
		switch (status)
		{
			case "approved":
				completeLink(poll.token);
				break;
			case "pending":
				schedulePoll(secret, deadline, intervalMs);
				break;
			case "denied":
				failLink("the link was denied in the browser");
				break;
			default:
				failLink("the link request is no longer valid");
				break;
		}
	}

	private void completeLink(String token)
	{
		if (token != null && !token.isEmpty())
		{
			// Write our own key as well as the shared slot, matching what the Exchange Insights
			// and Bank Templates plugins do when they perform a link. Neither of them mirrors a
			// token linked elsewhere, so neither does this: the box is populated when THIS
			// plugin did the linking, and stays empty when another one did.
			configManager.setConfiguration(ExchangeInsightsCaptureConfig.GROUP, "eiAccountToken", token);
			SharedAccountToken.set(configManager, token);
		}
		finishLinking();
		notifyChat("Exchange Insights Capture: your Exchange Insights account is linked.");
	}

	private void failLink(String reason)
	{
		finishLinking();
		javax.swing.JOptionPane.showMessageDialog(panel,
			"Couldn't link your account: " + reason + ".",
			"Not linked", javax.swing.JOptionPane.WARNING_MESSAGE);
	}

	private void finishLinking()
	{
		linking = false;
		setPanelLinking(false);
		final ExchangeInsightsCapturePanel p = panel;
		if (p != null)
		{
			javax.swing.SwingUtilities.invokeLater(p::refreshQuota);
		}
	}

	private void setPanelLinking(boolean value)
	{
		final ExchangeInsightsCapturePanel p = panel;
		if (p != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> p.setLinking(value));
		}
	}

	/** Unlink this character, after confirming. Mirrors the Bank Templates unlink semantics. */
	void startAccountUnlink()
	{
		if (uploader == null)
		{
			return;
		}
		final int choice = javax.swing.JOptionPane.showConfirmDialog(panel,
			"Unlink this character from your Exchange Insights account?\n\n"
				+ "Clip uploads stop for this character. Clips already uploaded stay until you\n"
				+ "delete them, and your account token keeps working for everything else.",
			"Unlink account", javax.swing.JOptionPane.OK_CANCEL_OPTION,
			javax.swing.JOptionPane.WARNING_MESSAGE);
		if (choice != javax.swing.JOptionPane.OK_OPTION)
		{
			return;
		}

		final String token = uploader.effectiveToken();
		clientThread.invokeLater(() ->
		{
			final long hash = client.getAccountHash();
			javax.swing.SwingUtilities.invokeLater(() -> uploader.unlinkIdentity(token, hash,
				() -> javax.swing.SwingUtilities.invokeLater(() ->
				{
					SharedAccountToken.clear(configManager);
					configManager.setConfiguration(ExchangeInsightsCaptureConfig.GROUP, "eiAccountToken", "");
					finishLinking();
					notifyChat("Exchange Insights Capture: this character is no longer linked.");
				}),
				error -> javax.swing.SwingUtilities.invokeLater(() -> failLink(error))));
		});
	}

	private void panelRefresh()
	{
		final ExchangeInsightsCapturePanel p = panel;
		if (p != null)
		{
			javax.swing.SwingUtilities.invokeLater(p::refresh);
		}
	}

	/** Redraw the clip list after the folder changes (a save, or a prune). */
	private void panelRefreshClips()
	{
		final ExchangeInsightsCapturePanel p = panel;
		if (p != null)
		{
			javax.swing.SwingUtilities.invokeLater(p::refreshClips);
		}
	}

	private void notifyChat(String message)
	{
		if (!config.notifyOnSave())
		{
			return;
		}
		clientThread.invokeLater(() -> client.addChatMessage(
			net.runelite.api.ChatMessageType.GAMEMESSAGE, "", message, null));
	}

	/**
	 * Gate for sampling frames at all. In Manual mode nothing is captured until the
	 * take is armed, which is what makes that mode free while idle.
	 */
	private boolean canCapture()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		switch (config.captureMode())
		{
			case OFF:
				return false;
			case MANUAL:
				return recorder != null && recorder.isSessionActive();
			default:
				return true;
		}
	}

	/** Arm or disarm a manual take; disarming encodes and saves everything captured. */
	void toggleManualSession()
	{
		if (recorder == null)
		{
			return;
		}
		if (config.captureMode() != CaptureMode.MANUAL)
		{
			notifyChat("Exchange Insights Capture: set Capture mode to Manual to use the arm hotkey.");
			return;
		}
		if (recorder.isSessionActive())
		{
			recorder.stopSession();
			notifyChat("Exchange Insights Capture: recording stopped, saving...");
		}
		else
		{
			recorder.startSession();
			notifyChat("Exchange Insights Capture: recording started.");
		}
		panelRefresh();
	}

	/**
	 * Where the game canvas sits on the desktop, for the screen-capture source.
	 *
	 * <p>getLocationOnScreen throws if the component is not showing, which happens routinely
	 * while the client is starting or minimised - so a null here simply means "skip this tick".
	 */
	/**
	 * Where the game canvas sits on screen, or null when the screen is not safe to copy.
	 *
	 * <p>Screen capture copies a rectangle of the desktop, not the game - it has no idea what is
	 * actually drawn there. So it is only offered while this window is the active one. Alt-tab to
	 * a browser and that rectangle now contains the browser: the plugin would quietly record
	 * whatever the player switched to, and put it in a clip they might upload.
	 *
	 * <p>Returning null is not a failure. The recorder falls back to asking the client for its own
	 * rendered frames, which show the game regardless of what is in front of it - slower, but
	 * correct and private. It also explains the "blank frames" warnings seen on Windows, where
	 * capture was faithfully recording a minimised or covered window.
	 */
	private java.awt.Rectangle canvasBoundsOnScreen()
	{
		try
		{
			final java.awt.Canvas canvas = client.getCanvas();
			if (canvas == null || !canvas.isShowing())
			{
				return null;
			}
			final java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(canvas);
			if (window == null || !window.isActive())
			{
				return null;
			}
			final java.awt.Point at = canvas.getLocationOnScreen();
			return new java.awt.Rectangle(at.x, at.y, canvas.getWidth(), canvas.getHeight());
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Mouse position as a fraction of the canvas (0..1 on each axis), or null if unknown.
	 *
	 * <p>Deliberately normalised rather than returned in pixels: the frame handed back by
	 * DrawManager is not necessarily the same size as the canvas the mouse coordinates are
	 * measured against - stretched mode and the HD renderer's scaling both break that
	 * assumption - so scaling pixels by the image's own dimensions put the marker in the
	 * wrong place. A fraction is independent of both sizes.
	 */
	private java.awt.geom.Point2D.Double mousePosition()
	{
		final net.runelite.api.Point p = client.getMouseCanvasPosition();
		if (p == null || p.getX() < 0 || p.getY() < 0)
		{
			return null;
		}

		// getCanvasWidth/Height is the GAME's canvas - the same space getMouseCanvasPosition
		// reports in, and the size DrawManager hands back. The AWT Canvas component is a
		// different size whenever stretched mode is on, so normalising against that put the
		// marker up and to the left of the real pointer.
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
		{
			final java.awt.Canvas canvas = client.getCanvas();
			if (canvas == null)
			{
				return null;
			}
			cw = canvas.getWidth();
			ch = canvas.getHeight();
		}
		if (cw <= 0 || ch <= 0)
		{
			return null;
		}
		return new java.awt.geom.Point2D.Double(p.getX() / (double) cw, p.getY() / (double) ch);
	}

	// ------------------------------------------------------------------
	// Triggers
	// ------------------------------------------------------------------

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (recorder == null || !(event.getActor() instanceof net.runelite.api.Player))
		{
			return;
		}
		final net.runelite.api.Player player = (net.runelite.api.Player) event.getActor();

		if (player == client.getLocalPlayer())
		{
			if (config.onDeath())
			{
				triggerClip(ClipTrigger.DEATHS, "Death");
			}
			return;
		}

		// Someone else's death only matters if you know them - otherwise a busy area would clip
		// constantly. Friends chat and clan are separate settings because they are separate
		// groups of people, and plenty of players want one and not the other.
		final boolean known = ((player.isFriendsChatMember() || player.isFriend()) && config.onFriendDeath())
			|| (player.isClanMember() && config.onClanDeath());
		if (known)
		{
			triggerClip(ClipTrigger.DEATHS, "Death " + player.getName());
		}
	}

	@Subscribe
	@SuppressWarnings("deprecation") // Skill.OVERALL is deprecated but still emitted; we skip it deliberately.
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == Skill.OVERALL)
		{
			return;
		}

		int level = event.getLevel();
		Integer previous = levels.put(skill, level);

		// Logging in fires one of these for every skill as the client fills them in, starting from
		// zero - so attack arrives as 0 and then as 99, which is indistinguishable from ninety-nine
		// levels gained at once. Nothing counts until the real levels have been read, which happens
		// a tick after LOGGED_IN; the "previous > 0" test then covers any straggler, since no skill
		// is ever genuinely levelled up from nothing.
		if (!levelsReady || previous == null || previous <= 0)
		{
			return;
		}

		if (config.onLevelUp() && level > previous && recorder != null)
		{
			triggerClip(ClipTrigger.LEVELS, skill.getName() + "(" + level + ")");
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (!config.onValuableDrop() || recorder == null)
		{
			return;
		}

		long value = 0;
		long bestValue = -1;
		String best = null;
		for (ItemStack stack : event.getItems())
		{
			final long stackValue = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
			value += stackValue;
			// The headline item is what makes the clip findable later - "drop-twisted-bow"
			// says far more than "loot" when scanning a folder full of captures.
			if (stackValue > bestValue)
			{
				bestValue = stackValue;
				best = itemManager.getItemComposition(stack.getId()).getName();
			}
		}

		if (value >= config.valuableDropThreshold())
		{
			triggerClip(ClipTrigger.VALUABLE_DROPS,
				best != null ? "Valuable drop " + best : "Valuable drop");
		}
	}

	/**
	 * Patterns lifted from RuneLite's screenshot plugin rather than written afresh.
	 *
	 * <p>These have absorbed years of corrections for message wording that changes between
	 * updates and differs per boss. Rewriting them would mean rediscovering all of that, and
	 * would drift from the plugin whose folder layout and file names this deliberately matches.
	 */
	private static final java.util.regex.Pattern BOSS_KILL = java.util.regex.Pattern.compile(
		"Your (.+) (?:kill|success) count is: ?<col=[0-9a-f]{6}>([0-9,]+)</col>");
	private static final java.util.regex.Pattern VALUABLE_DROP = java.util.regex.Pattern.compile(
		".*Valuable drop: ([^<>]+?\\(((?:\\d+,?)+) coins\\))(?:</col>)?");
	private static final java.util.regex.Pattern UNTRADEABLE_DROP = java.util.regex.Pattern.compile(
		".*Untradeable drop: ([^<>]+)(?:</col>)?");
	private static final java.util.regex.Pattern DUEL_END = java.util.regex.Pattern.compile(
		"You have now (won|lost) ([0-9,]+) duels?\\.");
	private static final java.util.regex.Pattern COMBAT_TASK = java.util.regex.Pattern.compile(
		"Congratulations, you've completed an? (?<tier>\\w+) combat task: <col=[0-9a-f]+>(?<task>(.+))</col>");
	private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("([,0-9]+)");
	private static final String COLLECTION_LOG_TEXT = "New item added to your collection log: ";
	private static final String CHEST_LOOTED_MESSAGE = "You find some treasure in the chest!";
	private static final java.util.List<String> PET_MESSAGES = java.util.Arrays.asList(
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed");

	/**
	 * Raids and chest bosses, which announce themselves differently from ordinary bosses.
	 *
	 * @return the clip name, e.g. "Chambers of Xeric(10)", or null if this is not one.
	 */
	private static String raidKill(String message)
	{
		final String plain = net.runelite.client.util.Text.removeTags(message);
		final String boss;
		if (plain.startsWith("Your completed Chambers of Xeric Challenge Mode count is:"))
		{
			boss = "Chambers of Xeric Challenge Mode";
		}
		else if (plain.startsWith("Your completed Chambers of Xeric count is:"))
		{
			boss = "Chambers of Xeric";
		}
		else if (plain.startsWith("Your completed Theatre of Blood"))
		{
			boss = plain.contains("Hard Mode") ? "Theatre of Blood Hard Mode"
				: plain.contains("Story Mode") ? "Theatre of Blood Story Mode" : "Theatre of Blood";
		}
		else if (plain.startsWith("Your completed Tombs of Amascut"))
		{
			boss = plain.contains("Expert Mode") ? "Tombs of Amascut Expert Mode"
				: plain.contains("Entry Mode") ? "Tombs of Amascut Entry Mode" : "Tombs of Amascut";
		}
		else if (plain.startsWith("Your Barrows chest count is"))
		{
			boss = "Barrows";
		}
		else if (plain.startsWith("Your Lunar Chest count is"))
		{
			boss = "Lunar Chest";
		}
		else
		{
			return null;
		}

		final java.util.regex.Matcher m = NUMBER.matcher(plain);
		return m.find() ? boss + "(" + m.group().replace(",", "") + ")" : boss;
	}

	/**
	 * A PvP kill, detected by receiving the victim's loot.
	 *
	 * <p>Not from their death: the client reports plenty of deaths you had nothing to do with,
	 * and the loot is what actually says the kill was yours.
	 */
	@Subscribe
	public void onPlayerLootReceived(net.runelite.client.events.PlayerLootReceived event)
	{
		if (recorder != null && config.onPvpKill())
		{
			triggerClip(ClipTrigger.PVP_KILLS, "Kill " + event.getPlayer().getName());
		}
	}

	/**
	 * Remember who a kick was aimed at.
	 *
	 * <p>The confirmation message that follows names nobody, so without catching the name here the
	 * clip could only be called "Kick".
	 */
	@Subscribe
	public void onScriptCallbackEvent(net.runelite.api.events.ScriptCallbackEvent e)
	{
		if (!"confirmFriendsChatKick".equals(e.getEventName()))
		{
			return;
		}
		final Object[] stack = client.getObjectStack();
		final int size = client.getObjectStackSize();
		if (size > 0 && stack[size - 1] instanceof String)
		{
			kickedPlayer = (String) stack[size - 1];
		}
	}

	/**
	 * Fill in the character's folder once the client actually knows who it is.
	 *
	 * <p>Reading it on the tick after LOGGED_IN is too early - the local player is not populated
	 * yet, so it came back null and the clips that followed were filed in the root of the save
	 * folder instead of under the account. Retried here until it is known, which costs a null
	 * check a tick thereafter.
	 */
	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick tick)
	{
		final ClipRecorder r = recorder;
		if (r != null && r.needsAccountFolder())
		{
			final String folder = accountFolder();
			if (folder != null)
			{
				r.setAccountFolder(folder);
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		if (recorder == null)
		{
			return;
		}
		// Only the two that announce themselves nowhere else. Everything the chat log reports is
		// handled there instead, which fires on the event rather than on the reward screen - for a
		// clip that matters, since the interesting footage is the fight, not the loot interface.
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.MISC_COLLECTION && config.onKingdom())
		{
			triggerClip(ClipTrigger.KINGDOM_REWARDS, "Kingdom " + java.time.LocalDate.now());
		}
		else if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.WILDY_LOOT_CHEST
			&& config.onWildernessLootChest())
		{
			triggerClip(ClipTrigger.WILDERNESS_LOOT_CHEST, "Loot chest");
		}
	}

	/** Who was last kicked from the friends chat, so the confirmation can name them. */
	private String kickedPlayer;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (recorder == null)
		{
			return;
		}

		switch (event.getType())
		{
			case GAMEMESSAGE:
			case SPAM:
			case TRADE:
			case FRIENDSCHATNOTIFICATION:
			case MESBOX:
				break;
			default:
				return;
		}

		final String message = event.getMessage();

		// Raids and chest bosses do not use the generic "kill count" wording, so they need their
		// own patterns - otherwise the biggest kills in the game are the ones that never clip.
		if (config.onBossKill())
		{
			final String raid = raidKill(message);
			if (raid != null)
			{
				triggerClip(ClipTrigger.BOSS_KILLS, raid);
				return;
			}
		}

		// "You have completed 251 Treasure Trails." - the count and the tier both come from here.
		if (config.onClueScroll() && message.contains("You have completed") && message.contains("Treasure"))
		{
			final java.util.regex.Matcher m = NUMBER.matcher(
				net.runelite.client.util.Text.removeTags(message));
			if (m.find())
			{
				final String plain = net.runelite.client.util.Text.removeTags(message);
				final int at = plain.lastIndexOf(m.group()) + m.group().length() + 1;
				final int end = plain.indexOf("Treasure");
				final String tier = at < end ? plain.substring(at, end - 1) : "Clue";
				triggerClip(ClipTrigger.CLUE_SCROLL_REWARDS,
					tier + "(" + m.group().replace(",", "") + ")");
				return;
			}
		}

		if (config.onBossKill())
		{
			final java.util.regex.Matcher m = BOSS_KILL.matcher(message);
			if (m.find())
			{
				final String boss = net.runelite.client.util.Text.removeTags(m.group(1));
				triggerClip(ClipTrigger.BOSS_KILLS, boss + "(" + m.group(2).replace(",", "") + ")");
				return;
			}
		}

		if (config.onChestLoot() && message.equals(CHEST_LOOTED_MESSAGE))
		{
			triggerClip(ClipTrigger.CHEST_LOOT, "Chest");
			return;
		}

		if (config.onPet() && PET_MESSAGES.stream().anyMatch(message::contains))
		{
			triggerClip(ClipTrigger.PETS, "Pet");
			return;
		}

		if (config.onValuableDrop())
		{
			final java.util.regex.Matcher m = VALUABLE_DROP.matcher(message);
			if (m.matches() && Integer.parseInt(m.group(2).replace(",", "")) >= config.valuableDropThreshold())
			{
				triggerClip(ClipTrigger.VALUABLE_DROPS, "Valuable drop " + m.group(1));
				return;
			}
		}

		if (config.onUntradeableDrop())
		{
			final java.util.regex.Matcher m = UNTRADEABLE_DROP.matcher(message);
			if (m.matches())
			{
				triggerClip(ClipTrigger.UNTRADEABLE_DROPS, "Untradeable drop " + m.group(1));
				return;
			}
		}

		if (config.onDuel())
		{
			final java.util.regex.Matcher m = DUEL_END.matcher(message);
			if (m.find())
			{
				triggerClip(ClipTrigger.DUELS,
					"Duel " + m.group(1) + " (" + m.group(2).replace(",", "") + ")");
				return;
			}
		}

		if (config.onCollectionLog() && message.startsWith(COLLECTION_LOG_TEXT))
		{
			final String entry = net.runelite.client.util.Text.removeTags(message)
				.substring(COLLECTION_LOG_TEXT.length());
			triggerClip(ClipTrigger.COLLECTION_LOG, "Collection log (" + entry + ")");
			return;
		}

		if (config.onCombatAchievement() && message.contains("combat task"))
		{
			final java.util.regex.Matcher m = COMBAT_TASK.matcher(message);
			if (m.find())
			{
				triggerClip(ClipTrigger.COMBAT_ACHIEVEMENTS,
					m.group("tier") + " combat task (" + net.runelite.client.util.Text.removeTags(m.group("task")) + ")");
				return;
			}
		}

		if (config.onLeagueTask() && message.contains("League Task Complete"))
		{
			triggerClip(ClipTrigger.LEAGUE_TASKS, "League task");
			return;
		}

		if (config.onQuestComplete() && message.contains("you've completed a quest"))
		{
			final int colon = message.indexOf(':');
			final String quest = colon >= 0 && colon + 1 < message.length()
				? net.runelite.client.util.Text.removeTags(message.substring(colon + 1)).trim()
				: null;
			triggerClip(ClipTrigger.QUESTS, quest != null && !quest.isEmpty() ? quest : "Quest");
			return;
		}

		if (config.onFriendsChatKick() && kickedPlayer != null
			&& message.equals("Your request to kick/ban this user was successful."))
		{
			triggerClip(ClipTrigger.FRIENDS_CHAT_KICKS, "Kick " + kickedPlayer);
			kickedPlayer = null;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				snapshotLevels();
				syncAccountFolder();
			});
			return;
		}

		// A new session replays every skill from scratch, so whatever levels we are holding are
		// about to be contradicted. Only the states that actually mean "starting again" count -
		// LOADING fires on every region change while logged in, and resetting on that would keep
		// throwing away the baseline a genuine level-up needs to be measured against.
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING
			|| state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			levelsReady = false;
			levels.clear();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// A token changing in EITHER sibling plugin can change what we resolve to, so
		// refresh the panel's link status for those as well as our own group.
		if (ClipUploader.isSharedTokenKey(event.getGroup(), event.getKey()))
		{
			// A different token means a different account, so the allowance must be re-read
			// from the server rather than carried over.
			final ExchangeInsightsCapturePanel p = panel;
			if (p != null)
			{
				javax.swing.SwingUtilities.invokeLater(p::refreshQuota);
			}
			return;
		}

		if (!ExchangeInsightsCaptureConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		// The framerate sets the capture scheduler's period. Re-arm rather than fully
		// restarting: the buffer is intentionally discarded (it would otherwise mix two
		// sample rates and play back at the wrong speed) but any in-flight encode survives.
		if ("framerate".equals(event.getKey()) && recorder != null)
		{
			recorder.restartCapture();
		}
		else if (("captureMicrophone".equals(event.getKey()) || "microphoneDevice".equals(event.getKey()))
			&& recorder != null)
		{
			// The microphone line is opened once and held, so changing device or switching it off
			// has to reopen it - the setting alone changes nothing that is already running.
			recorder.refreshAudio();
		}
		else if ("captureMode".equals(event.getKey()) && recorder != null
			&& config.captureMode() != CaptureMode.MANUAL && recorder.isSessionActive())
		{
			// Leaving Manual with a take still armed would strand it; save what we have.
			recorder.stopSession();
		}
		panelRefresh();
	}

	/**
	 * Record the levels the character actually has, and only then start watching for changes.
	 *
	 * <p>Runs a tick after login rather than immediately, because the client has not populated the
	 * skills at the moment the state changes. Until this has run, stat changes are recorded but
	 * never treated as level-ups.
	 */
	@SuppressWarnings("deprecation") // Skill.OVERALL is deprecated but still returned by Skill.values().
	private void snapshotLevels()
	{
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				levels.put(skill, client.getRealSkillLevel(skill));
			}
		}
		levelsReady = true;
	}

	private void onClipSaved(File file)
	{
		lastSavedAtMs = System.currentTimeMillis();
		log.info("Exchange Insights Capture saved clip to {}", file);
		notifyChat("Exchange Insights Capture saved: " + file.getName());
		panelRefresh();
		panelRefreshClips();

		// Prune before uploading: the local folder is the thing the player will notice
		// filling up, and pruning is cheap and local.
		try
		{
			ClipStorage.enforceLimit(config, this::notifyChat);
		}
		catch (RuntimeException e)
		{
			log.debug("storage limit enforcement failed", e);
		}

	}

	private void onClipError(String message)
	{
		log.warn("Exchange Insights Capture failed to save a clip: {}", message);
		notifyChat("Exchange Insights Capture could not save a clip: " + message);
		panelRefresh();
	}
}
