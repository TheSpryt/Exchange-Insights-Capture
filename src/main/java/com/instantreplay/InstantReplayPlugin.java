package com.instantreplay;

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
	name = "Instant Replay",
	description = "Automatically saves a video clip of the moments around in-game events like deaths and collection log unlocks",
	tags = {"record", "recording", "video", "clip", "replay", "death", "collection", "capture", "highlight"}
)
public class InstantReplayPlugin extends Plugin
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
	private InstantReplayConfig config;

	private ClipRecorder recorder;
	private InstantReplayOverlay overlay;
	private InstantReplayPanel panel;
	private NavigationButton navButton;
	private ClipUploader uploader;
	private volatile long lastSavedAtMs = Long.MIN_VALUE;
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);

	private final HotkeyListener manualHotkey = new HotkeyListener(() -> config.manualHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (recorder != null && config.captureMode() == CaptureMode.AUTO)
			{
				recorder.trigger("manual");
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
	InstantReplayConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(InstantReplayConfig.class);
	}

	@Override
	protected void startUp()
	{
		uploader = new ClipUploader(config, configManager, httpClient, executor, this::notifyChat);
		recorder = new ClipRecorder(config, drawManager, this::canCapture, this::mousePosition,
			this::onClipSaved, this::onClipError);
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
		overlay = new InstantReplayOverlay(this, config, this::canCapture,
			() -> recorder != null && recorder.isSessionActive(),
			() -> recorder == null ? 0 : recorder.getPendingEncodes(),
			() -> lastSavedAtMs);
		overlayManager.add(overlay);

		panel = new InstantReplayPanel(this, config, configManager, uploader);
		final BufferedImage icon = ImageUtil.loadImageResource(InstantReplayPlugin.class, "/com/instantreplay/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Instant Replay")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		keyManager.registerKeyListener(manualHotkey);
		keyManager.registerKeyListener(manualToggleHotkey);
		clientThread.invokeLater(this::snapshotLevels);
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
		if (overlay != null)
		{
			eventBus.post(new OverlayMenuClicked(
				new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Instant Replay"),
				overlay));
		}
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
			// One shared slot, so every plugin in the family is linked by this one action -
			// including any added later that has never heard of this one.
			SharedAccountToken.set(configManager, token);
		}
		finishLinking();
		notifyChat("Instant Replay: your Exchange Insights account is linked.");
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
		final InstantReplayPanel p = panel;
		if (p != null)
		{
			javax.swing.SwingUtilities.invokeLater(p::refreshQuota);
		}
	}

	private void setPanelLinking(boolean value)
	{
		final InstantReplayPanel p = panel;
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
					configManager.setConfiguration(InstantReplayConfig.GROUP, "eiAccountToken", "");
					finishLinking();
					notifyChat("Instant Replay: this character is no longer linked.");
				}),
				error -> javax.swing.SwingUtilities.invokeLater(() -> failLink(error))));
		});
	}

	private void panelRefresh()
	{
		final InstantReplayPanel p = panel;
		if (p != null)
		{
			javax.swing.SwingUtilities.invokeLater(p::refresh);
		}
	}

	/** Redraw the clip list after the folder changes (a save, or a prune). */
	private void panelRefreshClips()
	{
		final InstantReplayPanel p = panel;
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
			notifyChat("Instant Replay: set Capture mode to Manual to use the arm hotkey.");
			return;
		}
		if (recorder.isSessionActive())
		{
			recorder.stopSession();
			notifyChat("Instant Replay: recording stopped, saving...");
		}
		else
		{
			recorder.startSession();
			notifyChat("Instant Replay: recording started.");
		}
		panelRefresh();
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
		if (config.onDeath() && event.getActor() == client.getLocalPlayer() && recorder != null)
		{
			recorder.trigger("death");
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
		if (config.onLevelUp() && previous != null && level > previous && recorder != null)
		{
			recorder.trigger("level-" + skill.getName().toLowerCase() + "-" + level);
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
			recorder.trigger(best != null ? "drop-" + best : "drop");
		}
	}

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
			case MESBOX:
				break;
			default:
				return;
		}

		String message = event.getMessage().toLowerCase();

		if (config.onCollectionLog() && message.contains("added to your collection log"))
		{
			// "New item added to your collection log: Twisted bow"
			final String item = afterColon(event.getMessage());
			recorder.trigger(item != null ? "collog-" + item : "collection-log");
		}
		else if (config.onPet()
			&& (message.contains("funny feeling like you") || message.contains("weird sneaking into your backpack")))
		{
			recorder.trigger("pet");
		}
		else if (config.onQuestComplete() && message.contains("you've completed a quest"))
		{
			final String quest = afterColon(event.getMessage());
			recorder.trigger(quest != null ? "quest-" + quest : "quest");
		}
		else if (config.onCombatAchievement() && message.contains("combat task"))
		{
			// "Congratulations, you've completed a hard combat task: Defence in Depth."
			final String task = afterColon(event.getMessage());
			recorder.trigger(task != null ? "combat-task-" + task : "combat-task");
		}
	}

	/**
	 * The part of a game message after its colon - the item, task or quest it names - with
	 * any colour tags and trailing punctuation removed. Null when the message has no such tail.
	 */
	private static String afterColon(String message)
	{
		if (message == null)
		{
			return null;
		}
		final String plain = message.replaceAll("<[^>]*>", "");
		final int colon = plain.indexOf(':');
		if (colon < 0 || colon + 1 >= plain.length())
		{
			return null;
		}
		final String tail = plain.substring(colon + 1).trim().replaceAll("[.!]+$", "").trim();
		return tail.isEmpty() ? null : tail;
	}

	// ------------------------------------------------------------------
	// Lifecycle plumbing
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::snapshotLevels);
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
			final InstantReplayPanel p = panel;
			if (p != null)
			{
				javax.swing.SwingUtilities.invokeLater(p::refreshQuota);
			}
			return;
		}

		if (!InstantReplayConfig.GROUP.equals(event.getGroup()))
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
		else if ("captureMode".equals(event.getKey()) && recorder != null
			&& config.captureMode() != CaptureMode.MANUAL && recorder.isSessionActive())
		{
			// Leaving Manual with a take still armed would strand it; save what we have.
			recorder.stopSession();
		}
		panelRefresh();
	}

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
	}

	private void onClipSaved(File file)
	{
		lastSavedAtMs = System.currentTimeMillis();
		log.info("Instant Replay saved clip to {}", file);
		notifyChat("Instant Replay saved: " + file.getName());
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
		log.warn("Instant Replay failed to save a clip: {}", message);
		notifyChat("Instant Replay could not save a clip: " + message);
		panelRefresh();
	}
}
