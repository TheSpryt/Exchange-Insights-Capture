package com.exchangeinsightscapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Clip names come from a free-text rename box, so they are the one place a user decides what
 * lands on disk.
 *
 * <p>Worth pinning down: this rule existed in three near-copies that had already drifted apart -
 * one escaped a forward slash where it meant a backslash, so backslashes went straight into file
 * names - and nothing failed when that happened.
 */
public class SafeNameTest
{
	@Test
	public void stripsPathSeparators()
	{
		assertFalse(ClipStorage.safeName("..\\..\\windows\\system32").contains("\\"));
		assertFalse(ClipStorage.safeName("../../etc/passwd").contains("/"));
		// Dots may survive in the middle; they are only dangerous next to a separator, and every
		// separator is gone by then. Leading ones go because Windows would drop them anyway.
		assertEquals("_.._etc_passwd", ClipStorage.safeName("../../etc/passwd"));
	}

	@Test
	public void stripsShellMetacharacters()
	{
		// Legal in a file name, but the Windows reveal goes through cmd, which reads them first.
		for (String bad : new String[]{"a & b", "100%", "hi^there", "wow!", "back`tick", "$var"})
		{
			final String safe = ClipStorage.safeName(bad);
			assertFalse("left a shell character in " + safe, safe.matches(".*[&%^!`$;].*"));
		}
	}

	@Test
	public void stripsControlCharacters()
	{
		// Built rather than typed: raw control bytes in a source file make git treat it as binary,
		// and they are invisible to anyone reading the test.
		final String withControls = "a" + (char) 0x00 + "b" + (char) 0x1F + "c";
		assertEquals("abc", ClipStorage.safeName(withControls));
	}

	@Test
	public void handlesWindowsTrailingDotsAndSpaces()
	{
		// Windows drops these silently, so a name ending in them is not the name you get back.
		assertEquals("clip", ClipStorage.safeName("clip... "));
		assertEquals("clip", ClipStorage.safeName("  clip  "));
	}

	@Test
	public void escapesReservedDeviceNames()
	{
		// CON.mp4 cannot be created on Windows at all.
		assertEquals("CON_", ClipStorage.safeName("CON"));
		assertEquals("com1_", ClipStorage.safeName("com1"));
		assertEquals("console", ClipStorage.safeName("console"));
	}

	@Test
	public void boundsLength()
	{
		final StringBuilder long_ = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			long_.append('x');
		}
		assertTrue(ClipStorage.safeName(long_.toString()).length() <= 120);
	}

	@Test
	public void reportsWhenNothingSurvives()
	{
		// The caller has to notice this and refuse, rather than writing a file called "".
		assertEquals("", ClipStorage.safeName("   "));
		assertEquals("", ClipStorage.safeName(null));
		assertEquals("", ClipStorage.safeName("..."));

		// Not everything hostile ends up empty - separators become underscores, which is a
		// perfectly usable name. Only the "nothing left at all" case has to be refused.
		assertEquals("___", ClipStorage.safeName("///"));
	}

	@Test
	public void keepsParenthesesSoNamesRoundTrip()
	{
		// The clip naming format depends on these, and the server has to leave them alone too -
		// when it did not, every uploaded clip came back under a different name and the panel
		// listed it twice.
		assertEquals("Chambers of Xeric(267) 2026-08-14_22-42-14",
			ClipStorage.safeName("Chambers of Xeric(267) 2026-08-14_22-42-14"));
		assertEquals("Attack(99)", ClipStorage.safeName("Attack(99)"));
	}

	@Test
	public void allowsOnlyWhatTheServerKeeps()
	{
		// Mirrors the server's rule: word characters, dot, hyphen, parentheses, space.
		assertTrue(ClipStorage.safeName("a+b=c,d'e@f#g~h").matches("[A-Za-z0-9_.()\\- ]+"));
	}

	@Test
	public void leavesOrdinaryNamesAlone()
	{
		assertEquals("2026-08-14_20-49-31_manual", ClipStorage.safeName("2026-08-14_20-49-31_manual"));
		assertEquals("Zulrah pet drop", ClipStorage.safeName("Zulrah pet drop"));
	}
}
