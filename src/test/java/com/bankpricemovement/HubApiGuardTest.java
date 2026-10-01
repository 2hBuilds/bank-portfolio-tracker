package com.bankpricemovement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * The Plugin Hub's submission blockers as a TEST rather than as a promise that somebody greps (contract C46): the
 * packager's own {@code disallowed-apis.txt} and the idioms its reviewers flag, matched literally over every
 * {@code .java} under {@code src/main/java/<this test's package>}, with the COMMENTS removed first - a package
 * documents the rules it obeys ("never {@code execute}", "constructing one here is a Plugin Hub blocker"), and a raw
 * substring scan would red-fail on a compliant file the moment somebody wrote the rule down.
 *
 * <p><b>Written to be copied.</b> This file is the same in every 2hBuilds plugin, with nothing changed but its
 * {@code package} line: the folder it scans is derived from the package it is in (so a plugin in
 * {@code com.zenbank} scans {@code src/main/java/com/zenbank}), the comment stripper is its own, and it names no
 * helper of the plugin it sits in. Two guards against a vacuous pass: the package must hold at least one source,
 * and one of them must carry its own {@code package} line in the code left after the comments are gone.
 *
 * <p>Deliberately NOT in the list: {@code TimeUnit} and {@code Executors}, which are legitimate
 * ({@code f.get(timeout, TimeUnit.MILLISECONDS)}, {@code scheduleWithFixedDelay(..., TimeUnit.MILLISECONDS)}) - a
 * blanket "no concurrency" fragment would fail the suite on correct code. Nor {@code ImageUtil}: a plugin may ship
 * the brands' own marks, loaded the way every Hub plugin loads a resource - {@code ImageUtil.loadImageResource}. It is
 * not one of the packager's disallowed APIs. The two raw ways in, {@code Class.getResource(} and
 * {@code getResourceAsStream(}, stay blocked below: a resource goes through RuneLite's helper or not at all.
 */
public class HubApiGuardTest
{
	/**
	 * The exact text the Hub forbids anywhere in the package, spelled out: the fragments its submission checklist
	 * lists, plus the ways its prose rules ("no reflection", "no {@code Thread} construction", "no sleeps", "files
	 * only under the plugin's own directory") are actually written in Java.
	 */
	private static final List<String> HUB_BLOCKERS = Arrays.asList(
		"new Gson(",
		"new GsonBuilder(",
		"new OkHttpClient(",
		"OkHttpClient.Builder(",
		".execute()",
		"Class.getResource(",
		"getResourceAsStream(",
		"Class.forName(",
		"java.lang.reflect",
		".setAccessible(",
		"new Thread(",
		"Thread.sleep(",
		"System.getProperty(\"user.home\")",
		// The rest of the packager's disallowed-apis.txt (runelite/plugin-hub-tooling, read 2026-09-11) and the one
		// idiom its reviewers flag (re-asserting an interrupt in a catch block).
		".getVar(",
		"ChatMessageManager",
		"WidgetInfo",
		"WidgetID",
		"getItemStats(",
		"AccountClient",
		"AccountSession",
		"SessionManager",
		".interrupt(");

	@Test
	public void hubBlockersAreAbsentFromTheWholePackage() throws Exception
	{
		final String name = HubApiGuardTest.class.getName();
		final String pkg = name.substring(0, name.lastIndexOf('.'));
		final Path root = Paths.get("src", "main", "java").resolve(Paths.get("", pkg.split("\\.")));
		assertTrue("wrong working directory - no package at " + root.toAbsolutePath(), Files.isDirectory(root));

		final List<String> hits = new ArrayList<>();
		int scanned = 0;
		boolean sawMarker = false;
		final List<Path> sources;
		try (Stream<Path> walk = Files.walk(root))
		{
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		for (Path source : sources)
		{
			scanned++;
			final String code = withoutComments(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
			sawMarker |= code.contains("package " + pkg + ";");
			final String[] lines = code.split("\n", -1);
			for (int i = 0; i < lines.length; i++)
			{
				for (String blocker : HUB_BLOCKERS)
				{
					if (lines[i].contains(blocker))
					{
						hits.add(source.getFileName() + ":" + (i + 1) + "  " + blocker);
					}
				}
			}
		}

		assertTrue("Plugin Hub blockers in " + pkg + ":\n" + String.join("\n", hits), hits.isEmpty());
		assertTrue("no source file was scanned - the walk found the wrong tree", scanned >= 1);
		assertTrue("nothing recognisable was read: the scan proves nothing", sawMarker);
	}

	/** {@code code} with its line and block comments removed, newlines kept; string and char literals are left as written. */
	private static String withoutComments(String code)
	{
		final StringBuilder out = new StringBuilder(code.length());
		boolean inLine = false;
		boolean inBlock = false;
		boolean inString = false;
		boolean inChar = false;
		for (int i = 0; i < code.length(); i++)
		{
			final char c = code.charAt(i);
			final char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
			if (inLine)
			{
				if (c == '\n')
				{
					inLine = false;
					out.append(c);
				}
				continue;
			}
			if (inBlock)
			{
				if (c == '\n')
				{
					out.append(c);
				}
				else if (c == '*' && next == '/')
				{
					inBlock = false;
					i++;
				}
				continue;
			}
			if (inString || inChar)
			{
				out.append(c);
				if (c == '\\')
				{
					if (i + 1 < code.length())
					{
						out.append(next);
						i++;
					}
					continue;
				}
				inString = inString && c != '"';
				inChar = inChar && c != '\'';
				continue;
			}
			if (c == '/' && next == '/')
			{
				inLine = true;
				i++;
				continue;
			}
			if (c == '/' && next == '*')
			{
				inBlock = true;
				i++;
				continue;
			}
			inString = c == '"';
			inChar = c == '\'';
			out.append(c);
		}
		return out.toString();
	}
}
