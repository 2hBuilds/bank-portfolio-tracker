package com.bankpricemovement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Plugin Hub's file-I/O rule as a TEST rather than as a promise somebody greps (addendum AD).
 *
 * <p>{@code templateplugin/AGENTS.md} in {@code runelite/plugin-hub-tooling} says it in one line - "all file
 * i/o should be performed with {@code net.runelite.client.util.Filepath} rather than direct java APIs" - and a
 * maintainer asked for it by name on our submission, {@code runelite/plugin-hub#16536}. The point of
 * {@code Filepath} is that it is a SANDBOX: a {@code Filepath} cannot name anything outside the root it was
 * made from, so a plugin that only ever holds one is unable - not merely unwilling - to write outside its own
 * directory. The moment a {@code java.io.File} or a {@code java.nio.file.Path} appears in the shipped package
 * that property is gone, and nothing but a reviewer's eye would notice.
 *
 * <p>So this test fails the build instead, and it would have failed loudly on the morning of 2026-09-20, before
 * the port. {@code PriceStore} took a {@code java.io.File} directory and answered {@code File} targets, read
 * them with {@code Files.readAllBytes} and replaced them through {@code newBufferedWriter} and a
 * {@code Files.move} - its own comments still name all three, as history - and {@code BpmCommands.writeShot}
 * answered a {@code File} and handed it straight to {@code ImageIO.write}. That is four separate rules below,
 * and none of them had anything watching it.
 *
 * <p><b>Comments and strings are blanked before anything is matched</b>, because this package documents the
 * rules it obeys. {@code BpmCommands} explains in a comment why it does <em>not</em> use "ImageIO's File
 * overload", and {@code PriceStore}'s javadoc says its whole-file read "is what {@code Files.readAllBytes} did
 * before addendum AD". Both are correct code describing itself, and a raw substring scan would red-fail on them
 * - which trains the next author to delete the explanation rather than the mistake. A banned name inside a
 * string literal is blanked for the same reason: it is data, not a call. {@link #scrub} does the blanking, and
 * it is exercised by hand below so the scan cannot quietly stop seeing things.
 *
 * <p><b>Scope: {@code com.bankpricemovement} ONLY.</b> This workspace also holds {@code com.lootandbeam} and
 * {@code com.osrslos}, which are different plugins that are not being submitted and still use {@code java.io}
 * throughout. Do not widen the walk to {@code src/main/java} - it would turn the suite red over code this rule
 * does not reach, and the honest fix would then be to delete the test. One package is submitted; one package is
 * held to the rule.
 *
 * <p><b>What it guarantees, and what it cannot.</b> The scan reads the shipped source as TEXT and matches names
 * on word boundaries. What it therefore guarantees is exactly this: no file in {@code com.bankpricemovement}, at
 * any depth, NAMES one of the APIs listed in {@link #RULES} in code - and the five shapes that hide a name are
 * pinned by hand in {@link #aBannedNameIsCaughtHoweverItIsWritten()} (fully qualified with no import, a wildcard
 * import, a static import, a nested or anonymous class, and an identifier written with a unicode escape).
 *
 * <p>It does not type-check and it does not follow calls, so an API that takes a file NAME as a {@code String}
 * and never mentions {@code File}, {@code Path} or {@code Files} goes straight past it: {@code new
 * PrintWriter("bank.json")} and {@code new Formatter(name)} each open a file on disk while naming nothing this
 * test knows, as would {@code Runtime.getRuntime().exec(...)} or {@code System.load(...)}. Catching those would
 * mean type-checking the package, which is the compiler's job and not this one's. Two more are recorded here
 * rather than defended against: a unicode escape that decodes to a quote or to a slash can move where the
 * compiler thinks a string literal or a comment ENDS, which {@link #scrub} does not model, and anything reached
 * by reflection is invisible to a text scan. Both are work done to defeat this test rather than a mistake made
 * while writing the plugin, and the mistake is what this test is aimed at - it is the mistake that actually
 * happened, in four places, before addendum AD. The rest is the reviewer's eye and {@link PriceStoreTest},
 * which drives the real store over a temporary folder and would notice bytes landing outside it.
 *
 * <p><b>This test file itself imports {@code java.nio.file}</b>, which is exactly what it forbids next door.
 * That is not a loophole: the Hub never sees {@code src/test} (it is neither packaged nor scanned), a test has
 * no plugin instance to get a {@code Filepath} from, and reading the shipped source off disk is the one thing
 * this test is for. {@link TestFilepaths} makes the same argument for {@code Filepath.Unchecked}.
 */
public class FileIoRuleTest
{
	/** Where the shipped plugin lives, relative to the project root the tests run from. */
	private static final List<String> PACKAGE_PATH =
		Arrays.asList("src", "main", "java", "com", "bankpricemovement");

	/**
	 * How far up from the working directory {@link #packageRoot()} will look. Gradle runs tests with the
	 * project directory as the working directory, so zero is the answer in practice; an IDE that runs them
	 * from a module or build folder is why there is any search at all.
	 */
	private static final int SEARCH_PARENTS = 4;

	/**
	 * A floor on the walk. The package has 32 sources today and its surface is frozen (addendum AB), so a floor
	 * of 30 leaves room for one class being merged into another while still refusing a walk that quietly lost a
	 * tenth of the package. The floor was 25 until the addendum AD review: a walk that silently dropped seven
	 * files - a quarter of the plugin, {@code PriceStore} among them - would have passed green, which is the one
	 * way this test can lie.
	 */
	private static final int LEAST_SOURCES = 30;

	/**
	 * The files that must be among the ones read, by name. A count alone cannot tell a complete walk from one
	 * that found thirty of the wrong files, and these three are where every byte of the plugin's I/O lives:
	 * {@code PriceStore} owns the data directory and every load and save, {@code BpmCommands} writes the dev
	 * bridge's PNGs, and {@code BankPriceMovementPlugin} is the seam that hands each of them its
	 * {@code Filepath}. If the walk ever stops reaching one of the three, the suite says which one.
	 */
	private static final List<String> MUST_SCAN = Collections.unmodifiableList(Arrays.asList(
		"PriceStore.java", "BpmCommands.java", "BankPriceMovementPlugin.java"));

	/**
	 * A stand-in package tree for {@link #theWalkReachesASubPackage()}, and the only thing in this file that
	 * writes anything. Annotated with its full name because this file's own {@link Rule} class shadows
	 * {@code org.junit.Rule} inside the class body.
	 */
	@org.junit.Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	/** One banned name, and the {@link net.runelite.client.util.Filepath} call that replaces it. */
	private static final class Rule
	{
		private final Pattern pattern;
		/** What was found, in the words a reader would search for. */
		private final String label;
		/** What to write instead - the whole point of the failure message. */
		private final String instead;
		/**
		 * True when the rule is matched against source whose STRING literals are still there. Only
		 * {@code System.getProperty("user.home")} needs it: the thing that identifies the call is inside the
		 * string, so blanking it would blank the rule away with it.
		 */
		private final boolean overStrings;

		private Rule(final String regex, final String label, final String instead, final boolean overStrings)
		{
			this.pattern = Pattern.compile(regex);
			this.label = label;
			this.instead = instead;
			this.overStrings = overStrings;
		}

		private Rule(final String regex, final String label, final String instead)
		{
			this(regex, label, instead, false);
		}
	}

	/**
	 * Every direct file API the shipped package may not name, each carrying its replacement.
	 *
	 * <p>Matched on WORD BOUNDARIES, which is what makes the near-misses safe: {@code \bFile\b} does not fire
	 * on {@code Filepath}, on {@code FileAlreadyExistsException}, on {@code NoSuchFileException} or on
	 * {@code toFile()}, and {@code \bFiles\b} does not fire on {@code FileSystemException}. That precision is
	 * the difference between a rule and a nuisance, so {@link #theExactPairsTheRuleMustSeparate()} pins the
	 * pairs that matter by hand.
	 *
	 * <p>Deliberately NOT here, because they are part of {@code Filepath}'s own signatures or are not file
	 * access at all: {@code java.io.IOException}, {@code InputStream}, {@code OutputStream}, {@code Reader},
	 * {@code Writer}, {@code BufferedReader}, {@code BufferedWriter}, {@code StringReader},
	 * {@code InputStreamReader}, {@code java.nio.charset.StandardCharsets}, {@code StandardOpenOption},
	 * {@code StandardCopyOption}, {@code OpenOption}, {@code CopyOption}, and the {@code java.nio.file}
	 * EXCEPTION types ({@code NoSuchFileException}, {@code FileAlreadyExistsException},
	 * {@code AtomicMoveNotSupportedException}, {@code FileSystemException}) - {@code Filepath} throws those
	 * and the store catches them by name. {@code FileVisitOption} and {@code FileTime} are spared for the same
	 * reason: they are the parameter and return types of {@code Filepath.walk} and
	 * {@code Filepath.getLastModifiedTime}.
	 */
	private static final List<Rule> RULES = Collections.unmodifiableList(Arrays.asList(
		new Rule("\\bFile\\b", "java.io.File",
			"net.runelite.client.util.Filepath - Plugin.getPluginDirectory() for the root, then .join(name)"),
		new Rule("\\bFileWriter\\b", "java.io.FileWriter",
			"Filepath.write(text, StandardOpenOption...) or Filepath.openWriter(...)"),
		new Rule("\\bFileReader\\b", "java.io.FileReader",
			"Filepath.openReader(), or Filepath.openInputStream() when the bytes must be read whole"),
		new Rule("\\bFileInputStream\\b", "java.io.FileInputStream", "Filepath.openInputStream()"),
		new Rule("\\bFileOutputStream\\b", "java.io.FileOutputStream",
			"Filepath.openOutputStream(StandardOpenOption...)"),
		new Rule("\\bRandomAccessFile\\b", "java.io.RandomAccessFile",
			"nothing - Filepath has no random access on purpose; read the file whole and replace it whole,"
				+ " the way PriceStore.writeAtomic does"),
		new Rule("\\bFiles\\b", "java.nio.file.Files",
			"the Filepath method for the same job: exists(), isDirectory(), createDirectories(), delete(),"
				+ " deleteIfExists(), moveTo(), walk(), openInputStream(), openOutputStream(), write()"),
		new Rule("\\bPaths\\b", "java.nio.file.Paths",
			"nothing - a plugin never builds an absolute path; start at Plugin.getPluginDirectory() and join()"),
		new Rule("\\bPath\\b", "java.nio.file.Path", "hold a Filepath instead; it is the sandboxed one"),
		new Rule("\\bFileSystems\\b", "java.nio.file.FileSystems",
			"nothing - Filepath already names the only file system a plugin may touch"),
		new Rule("\\bRUNELITE_DIR\\b", "RuneLite.RUNELITE_DIR",
			"Plugin.getPluginDirectory(), which roots itself at ~/.runelite/plugin-data/<internalName>/ and"
				+ " moves any legacyDataDirectory there once"),
		new Rule("\\bPLUGIN_DATA\\b", "RuneLite.PLUGIN_DATA",
			"Plugin.getPluginDirectory() - the plugin is handed its own directory, it does not go looking"),
		new Rule("\\bUnchecked\\b", "Filepath.Unchecked",
			"the Filepath the plugin was handed (PriceStore.Directory is the seam). Unchecked escapes the"
				+ " sandbox, and RuneLite's own javadoc says using it stops a Hub plugin being reviewed"
				+ " automatically. It is allowed in src/test only - see TestFilepaths"),
		// Qualified on purpose. Filepath has a createTempFile(prefix, suffix) of its OWN, which makes the temp
		// file inside the sandbox and is exactly right; a bare \bcreateTempFile\b would refuse the correct call
		// along with the wrong one.
		new Rule("\\bFile\\s*\\.\\s*createTempFile\\b", "File.createTempFile",
			"Filepath.createTempFile(prefix, suffix) inside your own directory, or a temp NAME moved into"
				+ " place - PriceStore.writeAtomic"),
		new Rule("import\\s+java\\.io\\s*\\.\\s*\\*", "a wildcard import of java.io",
			"named imports, so this rule can tell java.io.IOException from java.io.File"),
		new Rule("import\\s+java\\.nio\\.file\\s*\\.\\s*\\*", "a wildcard import of java.nio.file",
			"named imports, so this rule can tell StandardOpenOption from Files"),
		new Rule("System\\s*\\.\\s*getProperty\\s*\\(\\s*\"user\\.(?:home|dir)\"",
			"System.getProperty(\"user.home\") / (\"user.dir\")",
			"Plugin.getPluginDirectory() - a plugin is told where it may write and never guesses", true),
		// A unicode escape is decoded before the compiler lexes anything, so an identifier whose letters are
		// written as escapes IS the banned name to javac while reading as nothing at all to every rule above,
		// which sees the file as written. Rather than decode them - which would mean writing half a lexer - the
		// package is simply not allowed to write one in code. It never needs to: the plugin's identifiers are
		// ASCII. String and character literals are blanked before this runs, so a genuine escape inside one
		// (GuideSnapshot's non-breaking space, BankPriceMovementPanel's format string) is untouched, and an
		// escape inside a literal is a known limit named in this class's javadoc rather than a rule.
		new Rule("\\\\u+[0-9a-fA-F]{4}", "a unicode escape in code",
			"the plain ASCII name. An escaped identifier - '\\u0046ile' is 'File' by the time javac reads it -"
				+ " hides from every rule above, so it is refused outright rather than decoded")));

	/** The call, up to and including its opening bracket; the arguments are walked by hand from there. */
	private static final Pattern IMAGEIO_WRITE = Pattern.compile("ImageIO\\s*\\.\\s*write\\s*\\(");

	/**
	 * What a LAST argument to {@code ImageIO.write} must not look like. The three-argument overloads are
	 * {@code (image, format, OutputStream)}, {@code (image, format, File)} and
	 * {@code (image, format, ImageOutputStream)}; only the stream ones can be pointed at a {@code Filepath},
	 * and {@code toFile()} / {@code toPath()} are the two ways a sandboxed path is thrown away on the way in.
	 */
	private static final Pattern NOT_A_STREAM =
		Pattern.compile("\\bFile\\b|\\bPath\\b|toFile\\s*\\(|toPath\\s*\\(");

	// ---------------------------------------------------------------- the real scan

	/**
	 * Every shipped source of {@code com.bankpricemovement}, held to every rule above.
	 *
	 * <p>Two guards against a vacuous pass, because the only way this test can lie is by scanning less than it
	 * claims to: the file count against {@link #LEAST_SOURCES}, and every name in {@link #MUST_SCAN} - the three
	 * classes that do the plugin's file work - being among the files actually read. A walk that finds the wrong
	 * tree fails in {@link #packageRoot()} before either of them, and one that reads only the top of the right
	 * tree is what {@link #theWalkReachesASubPackage()} rules out.
	 */
	@Test
	public void theShippedPackageDoesNoDirectFileIo() throws IOException
	{
		final Path root = packageRoot();
		final List<Path> sources = sourcesUnder(root);

		assertTrue("only " + sources.size() + " .java files under " + root.toAbsolutePath() + ", and there are "
			+ LEAST_SOURCES + " at the very least - the walk found the wrong tree or lost part of the right one,"
			+ " and a scan of a fraction of the package proves nothing about the rest",
			sources.size() >= LEAST_SOURCES);

		final List<String> names = new ArrayList<>();
		final List<String> hits = new ArrayList<>();
		for (final Path source : sources)
		{
			final String name = source.getFileName().toString();
			names.add(name);
			hits.addAll(findings(name, read(source)));
		}

		for (final String must : MUST_SCAN)
		{
			assertTrue(must + " was not among the " + names.size() + " files scanned under "
				+ root.toAbsolutePath() + " - this test cannot vouch for a file it never read, and that one"
				+ " does file I/O", names.contains(must));
		}

		assertTrue("Direct file I/O in com.bankpricemovement. The Plugin Hub requires every byte to go through"
			+ " net.runelite.client.util.Filepath (templateplugin/AGENTS.md), and each line below names what to"
			+ " write instead:\n\n" + String.join("\n", hits) + "\n", hits.isEmpty());
	}

	/**
	 * The port is really in place, not merely undetectable. Absence of the banned names would also be
	 * satisfied by a class that does no I/O at all, or by one that got it through a helper somewhere else, so
	 * the two classes that own the plugin's files are asked to name {@code Filepath} out loud.
	 */
	@Test
	public void theStoreAndTheBridgeImportFilepath() throws IOException
	{
		final Path root = packageRoot();
		final Pattern imported = Pattern.compile("import\\s+net\\.runelite\\.client\\.util\\.Filepath\\s*;");
		for (final String name : Arrays.asList("PriceStore.java", "BpmCommands.java"))
		{
			final String code = scrub(read(root.resolve(name)), false);
			assertTrue(name + " does not import net.runelite.client.util.Filepath - either the port was undone"
				+ " or its file work moved somewhere this test does not look",
				imported.matcher(code).find());
		}
	}

	/**
	 * The walk is by DEPTH, not a directory listing. Nothing in {@code com.bankpricemovement} sits in a
	 * sub-package today, but a sub-package would be shipped in the jar exactly like the rest of it, so a walk
	 * that read only the top level would leave the next author a corner where no rule looks. Proved over a
	 * temporary tree rather than the real one, because the real one cannot demonstrate depth it does not have.
	 */
	@Test
	public void theWalkReachesASubPackage() throws IOException
	{
		final Path root = tmp.newFolder("pkg").toPath();
		final Path sub = root.resolve("deep");
		Files.createDirectories(sub);
		write(root.resolve("Top.java"), "class Top { }\n");
		write(sub.resolve("Deep.java"), "class Deep { }\n");
		write(sub.resolve("notes.txt"), "not source\n");

		// Sorted by NAME here, not by path: Path.compareTo is case-insensitive on Windows and case-sensitive
		// elsewhere, which would order these two differently on the two platforms.
		final List<String> found = sourcesUnder(root).stream()
			.map(p -> p.getFileName().toString())
			.sorted()
			.collect(Collectors.toList());
		assertTrue("the walk stopped at the top level and never saw the sub-package: " + found,
			found.contains("Deep.java"));
		assertEquals("the walk should find both sources and nothing else: " + found,
			Arrays.asList("Deep.java", "Top.java"), found);
	}

	// ---------------------------------------------------------------- the matcher, by hand

	/**
	 * The stripper must not move a single character, because every line number this test reports is counted in
	 * the ORIGINAL source. Blanking in place - every stripped character replaced by a space, every newline
	 * kept - is what makes an index into the scrubbed text an index into the real file.
	 */
	@Test
	public void scrubbingKeepsEveryIndexWhereItWas()
	{
		final String source = sample(
			"/* java.nio.file.Files */",
			"class A // java.io.File",
			"{",
			"\tString s = \"java.io.File\";",
			"\tchar c = '\\\\';",
			"}");
		for (final boolean keepStrings : new boolean[]{false, true})
		{
			final String scrubbed = scrub(source, keepStrings);
			assertEquals("scrubbing changed the length, so every line number is now a guess",
				source.length(), scrubbed.length());
			assertEquals("scrubbing ate a newline", count(source, '\n'), count(scrubbed, '\n'));
		}
	}

	/**
	 * This package writes the rules down in its own comments - {@code BpmCommands} explains why it avoids
	 * "ImageIO's File overload", {@code PriceStore} says its read "is what {@code Files.readAllBytes} did".
	 * Both are correct code, and failing them would teach the next author to delete the explanation.
	 */
	@Test
	public void aBannedNameInACommentDoesNotCount()
	{
		final String source = sample(
			"class A",
			"{",
			"\t// ImageIO's File overload would open the file itself, outside the sandbox.",
			"\t/* This is what Files.readAllBytes did before addendum AD. */",
			"\t/**",
			"\t * {@code java.nio.file.Paths} and {@code RuneLite.RUNELITE_DIR} are gone: see Filepath.",
			"\t */",
			"\tvoid go() { }",
			"}");
		assertEquals(Collections.emptyList(), findings("Sample.java", source));
	}

	/**
	 * The same names, in code, with nothing to hide behind - and every occurrence reported, not one per file,
	 * because an import that was fixed while its call site survived would otherwise read as fixed.
	 *
	 * <p>{@code toPath()} is {@code java.nio.file.Path} by another name and is deliberately NOT a hit of its
	 * own: there is no word boundary in front of {@code Path} there, and a rule that guessed would fire on
	 * every method whose name happens to end in one. The {@code File} it is called on is the hit, and fixing
	 * that removes the {@code toPath()} with it.
	 */
	@Test
	public void aBannedNameInCodeCounts()
	{
		final List<String> hits = findings("Sample.java", sample(
			"import java.io.File;",
			"import java.nio.file.Files;",
			"class A",
			"{",
			"\tvoid go() throws Exception { Files.readAllBytes(new File(\"x\").toPath()); }",
			"}"));
		assertTrue("java.io.File went unnoticed: " + hits, hits.stream().anyMatch(h -> h.contains("java.io.File")));
		assertTrue("java.nio.file.Files went unnoticed: " + hits,
			hits.stream().anyMatch(h -> h.contains("java.nio.file.Files")));
		// Four: the two imports, and the two uses on line 5.
		assertEquals("every occurrence must be reported, not one per file: " + hits, 4, hits.size());
		assertEquals("the import of java.io.File is on line 1: " + hits,
			1, hits.stream().filter(h -> h.startsWith("Sample.java:1  ")).count());
		assertEquals("both uses on line 5 are reported: " + hits,
			2, hits.stream().filter(h -> h.startsWith("Sample.java:5  ")).count());
	}

	/** A banned name inside a string literal is data, not a call. */
	@Test
	public void aBannedNameInAStringDoesNotCount()
	{
		final String source = sample(
			"class A",
			"{",
			"\tstatic final String WHY = \"java.io.File and java.nio.file.Paths are banned\";",
			"\tvoid go() { log.warn(\"could not read Files, RUNELITE_DIR, Unchecked\"); }",
			"}");
		assertEquals(Collections.emptyList(), findings("Sample.java", source));
	}

	/**
	 * {@code System.getProperty("user.home")} is the one rule that reads the strings, because the thing that
	 * identifies the call is inside one. Blanking string literals for every rule would have blanked this rule
	 * out of existence.
	 */
	@Test
	public void userHomeIsCaughtThroughItsOwnString()
	{
		final List<String> home = findings("Sample.java", sample(
			"class A",
			"{",
			"\tstatic final String DIR = System.getProperty(\"user.home\") + \"/.runelite\";",
			"}"));
		assertEquals("exactly one hit, on line 3: " + home, 1, home.size());
		assertTrue(home.get(0), home.get(0).startsWith("Sample.java:3"));
		assertTrue(home.get(0), home.get(0).contains("user.home"));

		assertFalse("user.dir went unnoticed", findings("Sample.java", sample(
			"class A",
			"{",
			"\tstatic final String HERE = System.getProperty( \"user.dir\" );",
			"}")).isEmpty());

		assertEquals("an ordinary property is not file access", Collections.emptyList(),
			findings("Sample.java", sample(
				"class A",
				"{",
				"\tstatic final String NL = System.getProperty(\"line.separator\");",
				"}")));
	}

	/**
	 * The near-misses, one pair at a time. Each left-hand name must be caught and each right-hand name must
	 * not, and both halves of every pair really appear in the shipped package: {@code PriceStore} imports
	 * {@code java.io.IOException}, {@code NoSuchFileException}, {@code FileAlreadyExistsException},
	 * {@code AtomicMoveNotSupportedException} and {@code StandardCopyOption}, and holds {@code Filepath}
	 * fields. If the boundaries ever slip, the suite goes red here rather than on thirty-two innocent files.
	 */
	@Test
	public void theExactPairsTheRuleMustSeparate()
	{
		banned("import java.io.File;", "java.io.File");
		allowed("import java.io.IOException;");

		banned("import java.nio.file.Files;", "java.nio.file.Files");
		allowed("import java.nio.file.FileAlreadyExistsException;");
		allowed("import java.nio.file.NoSuchFileException;");
		allowed("import java.nio.file.AtomicMoveNotSupportedException;");
		allowed("import java.nio.file.FileSystemException;");

		banned("import java.nio.file.Path;", "java.nio.file.Path");
		banned("import java.nio.file.Paths;", "java.nio.file.Paths");
		banned("import java.nio.file.FileSystems;", "java.nio.file.FileSystems");
		allowed("import java.nio.file.StandardOpenOption;");
		allowed("import java.nio.file.StandardCopyOption;");
		allowed("import java.nio.file.OpenOption;");
		allowed("import java.nio.file.CopyOption;");

		banned("import java.io.FileWriter;", "java.io.FileWriter");
		banned("import java.io.FileReader;", "java.io.FileReader");
		banned("import java.io.FileInputStream;", "java.io.FileInputStream");
		banned("import java.io.FileOutputStream;", "java.io.FileOutputStream");
		banned("import java.io.RandomAccessFile;", "java.io.RandomAccessFile");
		allowed("import java.io.InputStream;");
		allowed("import java.io.OutputStream;");
		allowed("import java.io.Reader;");
		allowed("import java.io.Writer;");
		allowed("import java.io.BufferedReader;");
		allowed("import java.io.BufferedWriter;");
		allowed("import java.io.StringReader;");
		allowed("import java.io.InputStreamReader;");
		allowed("import java.nio.charset.StandardCharsets;");

		// Filepath itself must survive every File-shaped rule, or the port would be unwritable. Its own API is
		// full of File-shaped names - getFileName(), openFileChannel(), createTempFile(), FileVisitOption,
		// FileTime - and every one of them is the sandboxed way to do the thing the rule above bans.
		allowed("import net.runelite.client.util.Filepath;");
		allowed("\tprivate Filepath dir; void go() { dir.getFileName(); dir.join(\"a\"); }");
		allowed("\tvoid go() throws Exception { dir.createTempFile(\"bpm\", \".tmp\"); }");
		allowed("\tvoid go() throws Exception { dir.openFileChannel(StandardOpenOption.READ); }");
		allowed("import java.nio.file.FileVisitOption;");
		allowed("import java.nio.file.attribute.FileTime;");

		banned("\tprivate final File dir = new File(\"x\");", "java.io.File");
		banned("\tvoid go() { Filepath.Unchecked.getRooted(root); }", "Filepath.Unchecked");
		banned("\tvoid go() { RuneLite.RUNELITE_DIR.mkdirs(); }", "RuneLite.RUNELITE_DIR");
		banned("\tvoid go() throws Exception { File.createTempFile(\"a\", \"b\"); }", "File.createTempFile");
		banned("import java.io.*;", "a wildcard import of java.io");
		banned("import java.nio.file.*;", "a wildcard import of java.nio.file");
	}

	/**
	 * The five shapes a banned name can arrive in that are not a plain named import, each pinned by hand. A
	 * reader deciding whether to trust this test asks "what if it were written like THIS?", and these are the
	 * answers - they are also the list the class javadoc points at when it says what the scan guarantees.
	 */
	@Test
	public void aBannedNameIsCaughtHoweverItIsWritten()
	{
		// 1. Fully qualified, no import at all. The dot in front of the name is a word boundary, so the rule
		// fires on the last segment exactly as it would on an import line.
		banned("\tvoid go(java.io.File f) { }", "java.io.File");
		banned("\tvoid go() throws Exception { java.nio.file.Files.delete(p); }", "java.nio.file.Files");

		// 2. A wildcard import, which hides WHICH names were brought in - it has a rule of its own above, and
		// that rule is why the named-import rules can be as narrow as they are.
		banned("import java.io.*;", "a wildcard import of java.io");

		// 3. A static import names the class it imports FROM, so the import line itself is the hit even though
		// every use site afterwards reads as a bare method call.
		banned("import static java.nio.file.Files.readAllBytes;", "java.nio.file.Files");
		banned("import static java.io.File.createTempFile;", "java.io.File");

		// 4. Nested and anonymous classes. The scan is over the text of the file, so depth means nothing to it -
		// which is the one advantage a text scan has over a reader skimming for imports.
		banned("\tstatic class Inner { private final File dir = new File(\"x\"); }", "java.io.File");
		banned("\tRunnable r = new Runnable() { public void run() { Files.delete(p); } };",
			"java.nio.file.Files");

		// 5. An identifier whose letters are written as unicode escapes. The compiler decodes those before it
		// lexes anything, so the name it compiles is not the name on the page and no rule above can see it; the
		// escape itself is therefore refused. Written with a doubled backslash so THIS file's compiler leaves it
		// alone - the sample below is the six characters an author would type, not the letter they decode to.
		banned("\tvoid go(java.io.\\u0046ile f) { }", "a unicode escape in code");
		banned("\tvoid go() { \\u0046iles.delete(p); }", "a unicode escape in code");

		// And the legitimate escapes the package really contains, which live inside literals and must not fire:
		// GuideSnapshot's non-breaking space and BankPriceMovementPanel's format string.
		allowed("\tprivate static final Pattern WS = Pattern.compile(\"[\\\\s\\\\u00A0]+\");");
		allowed("\tvoid go(char c) { sb.append(String.format(\"\\\\u%04x\", (int) c)); }");
	}

	/**
	 * {@code ImageIO.write}'s three-argument form takes either a stream or a {@code File}, and only the stream
	 * one can be pointed at a {@code Filepath}. The shipped call is
	 * {@code ImageIO.write(img, "png", os)} into {@code out.openOutputStream(...)}, and this is what keeps it
	 * that way. Note the argument list is walked with the string literals already blanked, so the format name
	 * cannot smuggle a bracket or a comma into the parse.
	 */
	@Test
	public void imageIoMustBeHandedAStream()
	{
		assertEquals("a stream is the whole point of the port", Collections.emptyList(),
			findings("Sample.java", sample(
				"class A",
				"{",
				"\tvoid go() throws Exception { ImageIO.write(img, \"png\", os); }",
				"}")));

		final List<String> viaToFile = findings("Sample.java", sample(
			"class A",
			"{",
			"\tvoid go() throws Exception { ImageIO.write(img, \"png\", target.toFile()); }",
			"}"));
		assertEquals("throwing the sandbox away with toFile() must be exactly one hit: " + viaToFile,
			1, viaToFile.size());
		assertTrue(viaToFile.get(0), viaToFile.get(0).contains("ImageIO.write"));
		assertTrue(viaToFile.get(0), viaToFile.get(0).startsWith("Sample.java:3"));

		// TWO rules fire on this line - the java.io.File rule on the name, and the argument walk on what
		// ImageIO is being handed - and the assertion names the walk by its own words, because an assertion
		// that merely counted hits would be satisfied by the File rule alone and would still pass if the walk
		// stopped working. The division of labour between them is worth knowing: the walk reads the last
		// argument's TEXT and no further, so ImageIO.write(img, "png", someFile), where someFile is a File
		// declared a few lines up, is caught by the java.io.File rule on that declaration rather than here.
		final List<String> viaFile = findings("Sample.java", sample(
			"class A",
			"{",
			"\tvoid go() throws Exception { ImageIO.write(img, \"png\", new File(dir, \"a.png\")); }",
			"}"));
		assertTrue("ImageIO.write straight into a File went unnoticed: " + viaFile,
			viaFile.stream().anyMatch(h -> h.contains("ImageIO.write(...) into a file")));
	}

	/**
	 * A failure message has one job: let the next author fix the line without reading this test. It therefore
	 * names the file, the line number, what was found, the line it was found on, and what to write instead.
	 */
	@Test
	public void everyMessageNamesTheFileTheLineTheTextAndTheFix()
	{
		final List<String> hits = findings("PriceStore.java", sample(
			"class A",
			"{",
			"\tprivate static final File DIR = defaultDir();",
			"}"));
		assertEquals("one line, one hit: " + hits, 1, hits.size());

		final String message = hits.get(0);
		assertTrue("no file name: " + message, message.contains("PriceStore.java"));
		assertTrue("no line number: " + message, message.contains(":3"));
		assertTrue("does not say what was found: " + message, message.contains("java.io.File"));
		assertTrue("does not quote the line: " + message, message.contains("private static final File DIR"));
		assertTrue("does not say what to use instead: " + message, message.contains("Filepath"));
		assertTrue("does not say what to use instead: " + message, message.contains("instead"));
	}

	// ---------------------------------------------------------------- the matcher itself

	/** Every rule broken by one source file, as messages a reader can act on. */
	static List<String> findings(final String fileName, final String source)
	{
		final String code = scrub(source, false);
		final String withStrings = scrub(source, true);

		final List<String> out = new ArrayList<>();
		for (final Rule rule : RULES)
		{
			final Matcher m = rule.pattern.matcher(rule.overStrings ? withStrings : code);
			while (m.find())
			{
				out.add(report(fileName, source, m.start(), rule.label, rule.instead));
			}
		}
		out.addAll(imageIoFindings(fileName, source, code));
		return out;
	}

	/**
	 * {@code ImageIO.write} needs its ARGUMENTS read rather than its name: the stream overload is the one the
	 * port uses and must stay legal, while the {@code File} one is a hole straight through the sandbox. The
	 * call's bracket is matched by depth over source whose strings are already blanked, so a bracket or comma
	 * inside {@code "png"} cannot confuse the split.
	 */
	private static List<String> imageIoFindings(final String fileName, final String source, final String code)
	{
		final List<String> out = new ArrayList<>();
		final Matcher m = IMAGEIO_WRITE.matcher(code);
		while (m.find())
		{
			final int open = m.end() - 1;
			final int close = closingBracket(code, open);
			if (close < 0)
			{
				continue;
			}
			final String last = lastArgument(code.substring(open + 1, close));
			if (NOT_A_STREAM.matcher(last).find())
			{
				out.add(report(fileName, source, m.start(),
					"ImageIO.write(...) into a file rather than a stream - its last argument is '"
						+ last.trim() + "'",
					"ImageIO.write(image, \"png\", path.openOutputStream(StandardOpenOption.CREATE,"
						+ " StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) - the File"
						+ " overload opens the file itself, outside the sandbox"));
			}
		}
		return out;
	}

	/** The index of the bracket that closes the one at {@code open}, or -1 when nothing does. */
	private static int closingBracket(final String code, final int open)
	{
		int depth = 0;
		for (int i = open; i < code.length(); i++)
		{
			final char c = code.charAt(i);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
				if (depth == 0)
				{
					return i;
				}
			}
		}
		return -1;
	}

	/** The last top-level argument of an argument list, brackets of every kind counted so nesting is safe. */
	private static String lastArgument(final String args)
	{
		int depth = 0;
		int from = 0;
		for (int i = 0; i < args.length(); i++)
		{
			final char c = args.charAt(i);
			if (c == '(' || c == '[' || c == '{')
			{
				depth++;
			}
			else if (c == ')' || c == ']' || c == '}')
			{
				depth--;
			}
			else if (c == ',' && depth == 0)
			{
				from = i + 1;
			}
		}
		return args.substring(from);
	}

	/**
	 * One hit, in the shape the failure message promises: {@code PriceStore.java:12  what was found}, the line
	 * and then the replacement on its own indented line. The index comes from the SCRUBBED text and is used
	 * against the ORIGINAL, which is sound because {@link #scrub} never moves a character.
	 */
	private static String report(final String fileName, final String source, final int at, final String label,
		final String instead)
	{
		return fileName + ":" + lineOf(source, at) + "  " + label
			+ "\n        on: " + lineText(source, at)
			+ "\n        use instead: " + instead;
	}

	/** The 1-based line the character at {@code at} sits on. */
	private static int lineOf(final String source, final int at)
	{
		int line = 1;
		for (int i = 0; i < at && i < source.length(); i++)
		{
			if (source.charAt(i) == '\n')
			{
				line++;
			}
		}
		return line;
	}

	/** That whole line, trimmed - the offending text, as the author wrote it. */
	private static String lineText(final String source, final int at)
	{
		final int from = source.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
		int to = source.indexOf('\n', at);
		if (to < 0)
		{
			to = source.length();
		}
		return source.substring(Math.min(from, to), to).trim();
	}

	/**
	 * Block comments, line comments and (unless {@code keepStrings}) the CONTENTS of string and character
	 * literals, replaced character for character by spaces. Newlines are always kept, so the result has the
	 * same length and the same line breaks as the source and an index into one is an index into the other.
	 *
	 * <p>Order matters and is the whole subtlety: a {@code //} inside a string starts no comment, and a
	 * {@code "} inside a comment opens no string. Java 11 is the language level here (the Hub forces
	 * {@code options.release 11}), so there are no text blocks to worry about.
	 */
	static String scrub(final String source, final boolean keepStrings)
	{
		final char[] out = source.toCharArray();
		final int n = source.length();
		int i = 0;
		while (i < n)
		{
			final char c = source.charAt(i);
			final char next = i + 1 < n ? source.charAt(i + 1) : '\0';

			if (c == '/' && next == '/')
			{
				while (i < n && source.charAt(i) != '\n')
				{
					out[i] = ' ';
					i++;
				}
				continue;
			}

			if (c == '/' && next == '*')
			{
				out[i] = ' ';
				out[i + 1] = ' ';
				i += 2;
				while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/'))
				{
					if (source.charAt(i) != '\n')
					{
						out[i] = ' ';
					}
					i++;
				}
				if (i < n)
				{
					out[i] = ' ';
					i++;
				}
				if (i < n)
				{
					out[i] = ' ';
					i++;
				}
				continue;
			}

			if (c == '"' || c == '\'')
			{
				// The quotes themselves stay, so the shape of the code survives; only what is between them goes.
				i++;
				while (i < n)
				{
					final char d = source.charAt(i);
					if (d == '\n')
					{
						// No literal spans a line in Java 11: this is a stray quote, and eating the rest of the
						// file over it would blind the whole scan.
						break;
					}
					if (d == '\\')
					{
						if (!keepStrings)
						{
							out[i] = ' ';
							if (i + 1 < n && source.charAt(i + 1) != '\n')
							{
								out[i + 1] = ' ';
							}
						}
						i += 2;
						continue;
					}
					if (d == c)
					{
						i++;
						break;
					}
					if (!keepStrings)
					{
						out[i] = ' ';
					}
					i++;
				}
				continue;
			}

			i++;
		}
		return new String(out);
	}

	// ---------------------------------------------------------------- plumbing

	/**
	 * {@code src/main/java/com/bankpricemovement}, found from the working directory or one of its first few
	 * parents. Fails loudly rather than returning nothing: a scan that finds no files passes every rule, and a
	 * test that passes by scanning nothing is worse than no test at all.
	 */
	private static Path packageRoot()
	{
		final Path start = Paths.get("").toAbsolutePath();
		Path here = start;
		for (int up = 0; here != null && up <= SEARCH_PARENTS; up++, here = here.getParent())
		{
			final Path candidate = here.resolve(Paths.get(PACKAGE_PATH.get(0),
				PACKAGE_PATH.subList(1, PACKAGE_PATH.size()).toArray(new String[0])));
			if (Files.isDirectory(candidate))
			{
				return candidate;
			}
		}
		fail("could not find " + String.join("/", PACKAGE_PATH) + " from " + start + " or any of its first "
			+ SEARCH_PARENTS + " parents. This test exists to scan the shipped source, so it refuses to pass"
			+ " without having read it - run the suite from the project root.");
		return null;
	}

	/**
	 * Every {@code .java} file under {@code root}, at any depth, in a stable order. A sub-package ships in the
	 * same jar as the rest of the package, so it is held to the same rule.
	 */
	private static List<Path> sourcesUnder(final Path root) throws IOException
	{
		try (Stream<Path> walk = Files.walk(root))
		{
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
	}

	private static String read(final Path source) throws IOException
	{
		return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
	}

	/**
	 * UTF-8 into a new file. The only writing this test does, and it happens inside the {@link #tmp} folder:
	 * a test about where bytes are allowed to land has no business putting any of its own anywhere else.
	 */
	private static void write(final Path file, final String text) throws IOException
	{
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	/** One line of source wrapped in just enough class for the samples above to read like Java. */
	private static String sample(final String... lines)
	{
		return String.join("\n", lines) + "\n";
	}

	/**
	 * That one line, inside a class, must produce a hit whose LABEL is {@code label}. The label is looked for
	 * after the two spaces the report puts in front of it, not anywhere in the message: the quoted source line
	 * that follows would otherwise satisfy almost any label and the pairing would prove nothing.
	 */
	private static void banned(final String line, final String label)
	{
		final List<String> hits = findings("Sample.java", sample("package p;", line, "class A { }"));
		assertTrue("'" + line + "' should have been refused as " + label + ", got " + hits,
			hits.stream().anyMatch(h -> h.contains("  " + label + "\n")));
	}

	/** That one line must produce nothing at all: it is part of Filepath's own world. */
	private static void allowed(final String line)
	{
		assertEquals("'" + line + "' is allowed and must not be flagged",
			Collections.emptyList(), findings("Sample.java", sample("package p;", line, "class A { }")));
	}

	private static long count(final String text, final char c)
	{
		long n = 0;
		for (int i = 0; i < text.length(); i++)
		{
			if (text.charAt(i) == c)
			{
				n++;
			}
		}
		return n;
	}
}
