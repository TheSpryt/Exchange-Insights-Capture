package com.exchangeinsightscapture;

import java.awt.Desktop;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.OSType;

/**
 * Show a file in the operating system's file manager, selected.
 *
 * <p>There is no portable way to do this, so each platform gets what it actually understands, and
 * anything that does not work falls back to simply opening the containing folder - which is worse
 * but never wrong.
 */
@Slf4j
final class FileReveal
{
	/**
	 * Characters that make a Windows path unsafe to hand to the shell.
	 *
	 * <p>Windows is the one platform whose reveal has to go through cmd (see below), and cmd reads
	 * the path before Explorer does. Double quotes are already stripped when clips are named, but
	 * {@code &} and {@code %} are legal in file names and a user can type them when renaming - and
	 * both were measured breaking the reveal, landing on Desktop instead of the clip. Rather than
	 * mangle the path, such names skip selection and just open the folder.
	 */
	private static final java.util.regex.Pattern SHELL_UNSAFE =
		java.util.regex.Pattern.compile("[&%^!\"`]");

	private FileReveal()
	{
	}

	/** Reveal {@code file}, falling back to its folder whenever selection is not possible. */
	static void reveal(File file)
	{
		if (file == null)
		{
			return;
		}
		if (!file.isFile())
		{
			openFolder(file.getParentFile());
			return;
		}

		try
		{
			// The JDK can do this itself on macOS and on Linux desktops that implement it, and
			// asking it first means no process spawning and no quoting to get wrong.
			if (Desktop.isDesktopSupported()
				&& Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR))
			{
				Desktop.getDesktop().browseFileDirectory(file);
				return;
			}

			if (OSType.getOSType() == OSType.Windows)
			{
				revealWindows(file);
				return;
			}

			if (OSType.getOSType() == OSType.MacOS)
			{
				// Straight exec, no shell, so spaces and punctuation need no special handling.
				new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
				return;
			}
		}
		catch (Exception e)
		{
			log.debug("could not reveal {}", file, e);
		}

		openFolder(file.getParentFile());
	}

	/**
	 * Windows selection, which only works one way.
	 *
	 * <p>Measured, not assumed: {@code explorer.exe /select,<path>} through ProcessBuilder opens
	 * the user's Documents folder instead of selecting anything as soon as the path contains a
	 * space, because Java quotes the argument and Explorer's own parser does not expect that.
	 * Pre-quoting the argument and passing a relative path both fail the same way. Going through
	 * cmd is the form that actually works.
	 */
	private static void revealWindows(File file) throws java.io.IOException
	{
		final String path = file.getAbsolutePath();
		if (SHELL_UNSAFE.matcher(path).find())
		{
			openFolder(file.getParentFile());
			return;
		}
		new ProcessBuilder("cmd", "/c", "explorer.exe /select,\"" + path + "\"").start();
	}

	/** Last resort: the folder, without the clip picked out. */
	private static void openFolder(File dir)
	{
		if (dir == null)
		{
			return;
		}
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		LinkBrowser.open(dir.getAbsolutePath());
	}
}
