package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The Plugin Hub's rule "Reflection and using the management/runtime packages isn't allowed" (the user's question,
 * 2026-09-30), held over every {@code .java} under {@code src/main/java/<this test's package>} the way
 * {@link FileIoRuleTest} holds the file rule: comments and string literals are blanked first, so a banned name in a
 * comment or a message does not count, and any that remains fails the suite with the file, the line and the text.
 *
 * <p>Banned: {@code java.lang.reflect}, {@code java.lang.management}, {@code java.lang.instrument},
 * {@code Runtime.getRuntime}, {@code Class.forName}, {@code .getDeclaredMethod(}, {@code .getDeclaredField(},
 * {@code .getDeclaredConstructor(}, {@code .getMethod(}, {@code .getField(}, {@code setAccessible},
 * {@code MethodHandles}, {@code sun.misc}, {@code jdk.internal}. Gson does its own reflection inside RuneLite's
 * injected copy, which every Hub plugin uses; that is not this package's code and is not scanned.
 */
public class NoReflectionRuleTest
{
	/**
	 * {@code src/main/java/<this test's package>}: derived from the package this class is in, so the same file scans
	 * {@code com.zenbank} when it is copied there with only its {@code package} line changed.
	 */
	static final Path MAIN = Paths.get("src", "main", "java")
		.resolve(Paths.get("", packageName().split("\\.")));

	static final List<String> BANNED = Arrays.asList(
		"java.lang.reflect", "java.lang.management", "java.lang.instrument", "Runtime.getRuntime", "Class.forName",
		".getDeclaredMethod(", ".getDeclaredField(", ".getDeclaredConstructor(", ".getMethod(", ".getField(",
		"setAccessible", "MethodHandles", "sun.misc", "jdk.internal");

	private static String packageName()
	{
		final String name = NoReflectionRuleTest.class.getName();
		return name.substring(0, name.lastIndexOf('.'));
	}

	@Test
	public void theShippedPackageUsesNoReflectionAndNoManagementOrRuntimePackage() throws IOException
	{
		final List<String> findings = new ArrayList<>();
		int files = 0;
		try (Stream<Path> walk = Files.walk(MAIN))
		{
			for (final Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				files++;
				findings.addAll(findings(file.getFileName().toString(),
					new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
			}
		}
		assertTrue("the walk must reach the package", files > 20);
		assertEquals("reflection, management or runtime use in src/main:\n" + String.join("\n", findings),
			0, findings.size());
	}

	@Test
	public void aBannedNameInACommentOrAStringDoesNotCountButInCodeItDoes()
	{
		assertEquals(0, findings("A.java", "// uses Class.forName here\n/* and MethodHandles */\nString s = \"java.lang.reflect\";\n").size());
		final List<String> hits = findings("B.java", "int x = 1;\nfinal Runtime r = Runtime.getRuntime();\n");
		assertEquals(1, hits.size());
		assertTrue(hits.get(0), hits.get(0).startsWith("B.java:2: Runtime.getRuntime"));
	}

	/** Every banned name left in {@code source} once its comments and strings are blanked, as {@code file:line: name}. */
	static List<String> findings(final String fileName, final String source)
	{
		final String code = blank(source);
		final List<String> out = new ArrayList<>();
		for (final String banned : BANNED)
		{
			int at = code.indexOf(banned);
			while (at >= 0)
			{
				final int line = 1 + (int) code.substring(0, at).chars().filter(c -> c == '\n').count();
				out.add(fileName + ":" + line + ": " + banned);
				at = code.indexOf(banned, at + banned.length());
			}
		}
		return out;
	}

	/** {@code source} with every comment and every string or char literal replaced by spaces, newlines kept. */
	static String blank(final String source)
	{
		final StringBuilder out = new StringBuilder(source.length());
		int i = 0;
		final int n = source.length();
		while (i < n)
		{
			final char c = source.charAt(i);
			if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/')
			{
				while (i < n && source.charAt(i) != '\n')
				{
					out.append(' ');
					i++;
				}
			}
			else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*')
			{
				final int end = source.indexOf("*/", i + 2);
				final int stop = end < 0 ? n : end + 2;
				for (; i < stop; i++)
				{
					out.append(source.charAt(i) == '\n' ? '\n' : ' ');
				}
			}
			else if (c == '"' || c == '\'')
			{
				final char quote = c;
				out.append(' ');
				i++;
				while (i < n && source.charAt(i) != quote)
				{
					if (source.charAt(i) == '\\' && i + 1 < n)
					{
						out.append("  ");
						i += 2;
						continue;
					}
					out.append(source.charAt(i) == '\n' ? '\n' : ' ');
					i++;
				}
				out.append(' ');
				i++;
			}
			else
			{
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}
}
