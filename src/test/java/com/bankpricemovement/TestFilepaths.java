package com.bankpricemovement;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import net.runelite.client.util.Filepath;

/**
 * Turns a JUnit {@code TemporaryFolder} into the {@link Filepath} the plugin's classes now take, and reads and
 * writes one in a line (addendum AD).
 *
 * <p><b>Why {@code Filepath.Unchecked} is allowed HERE and nowhere else.</b> In the client a plugin gets its
 * directory from {@code Plugin.getPluginDirectory()}, which is {@code protected} on RuneLite's {@code Plugin}
 * and roots itself at {@code ~/.runelite/plugin-data/<internalName>/}. A unit test has no plugin instance and
 * must never touch the real {@code ~/.runelite}, so it makes its own root over a temporary folder - which is
 * exactly what {@code Unchecked.getRooted} is for. RuneLite's own javadoc warns that using {@code Unchecked}
 * stops a Plugin Hub plugin being reviewed automatically, and that warning is about SHIPPED code: the Hub
 * never sees {@code src/test} (it is not packaged and not scanned), and {@code FileIoRuleTest} proves no class
 * under {@code src/main} names it.
 *
 * <p>Test-only helper: it lives in the test source set and nothing in {@code src/main} may reference it.
 */
final class TestFilepaths
{
	private TestFilepaths()
	{
	}

	/** A {@link Filepath} rooted at {@code dir}, so nothing a test writes can land outside it. */
	static Filepath rooted(final File dir)
	{
		return Filepath.Unchecked.getRooted(dir.toPath());
	}

	/** {@code dir/name}, the way production builds every path: by joining onto the root. */
	static Filepath at(final File dir, final String name)
	{
		return rooted(dir).join(name);
	}

	/** Writes UTF-8 text, creating or replacing the file, and creating its directory if it is missing. */
	static void write(final Filepath file, final String text) throws IOException
	{
		final Filepath parent = file.getParent();
		if (!parent.isDirectory())
		{
			parent.createDirectories();
		}
		try (OutputStream out = file.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.TRUNCATE_EXISTING))
		{
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	/** The whole file as UTF-8 text. */
	static String read(final Filepath file) throws IOException
	{
		try (InputStream in = file.openInputStream())
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
