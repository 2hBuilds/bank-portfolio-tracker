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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * What THIS plugin holds its source tree to, beyond what the kit's rule tests ({@link FileIoRuleTest},
 * {@link NoReflectionRuleTest}, {@link HubApiGuardTest}) hold every 2hBuilds plugin to. Those three are the same file
 * in every plugin and so carry the weakest possible guard against a walk that read nothing - one source file. The
 * stronger guards belong to the plugin whose size and shape they describe, and live here, in a test that is NOT a kit
 * file and is not copied anywhere.
 *
 * <p><b>The floor.</b> The package has more than thirty sources and its surface is frozen (addendum AB), so a floor of
 * 30 leaves room for one class being merged into another while still refusing a walk that quietly lost a tenth of
 * the package. The floor was 25 until the addendum AD review: a walk that silently dropped seven files - a quarter of
 * the plugin, {@code PriceStore} among them - would have passed green, which is the one way a text scan can lie.
 *
 * <p><b>The three names.</b> A count alone cannot tell a complete walk from one that found thirty of the wrong files,
 * and these three are where every byte of the plugin's I/O lives: {@code PriceStore} owns the data directory and every
 * load and save, {@code BpmCommands} writes the dev bridge's PNGs, and {@code BankPriceMovementPlugin} is the seam that
 * hands each of them its {@code Filepath}. If the walk ever stops reaching one of the three, the suite says which one.
 *
 * <p><b>The port is really in place</b>, not merely undetectable: absence of the banned names would also be satisfied
 * by a class that does no I/O at all, or by one that got it through a helper somewhere else, so the two classes that
 * hold the plugin's files are asked to name {@code Filepath} out loud.
 */
public class BankRuleFloorsTest
{
	/** The package's sources, at the very least: see the class comment. */
	private static final int LEAST_SOURCES = 30;

	/** The compiled classes of the package, at the very least: the walk of {@link NoTestOnlyMembersRuleTest}. */
	private static final int LEAST_CLASSES = 30;

	/** The files that must be among the ones read, by name: see the class comment. */
	private static final List<String> MUST_SCAN = Collections.unmodifiableList(Arrays.asList(
		"PriceStore.java", "BpmCommands.java", "BankPriceMovementPlugin.java"));

	@Test
	public void theWalkReadsEnoughSourcesClassesAndTheThreeClassesThatDoFileWork() throws Exception
	{
		final Path root = packageRoot();
		final List<Path> sources = sourcesUnder(root);

		assertTrue("only " + sources.size() + " .java files under " + root.toAbsolutePath() + ", and there are "
			+ LEAST_SOURCES + " at the very least - the walk found the wrong tree or lost part of the right one,"
			+ " and a scan of a fraction of the package proves nothing about the rest",
			sources.size() >= LEAST_SOURCES);

		// The compiled side, for the test-only-members rule: its own floor is one class, this is the plugin's.
		final int classes = compiledClasses();
		assertTrue("the walk found the package's compiled classes: " + classes, classes > LEAST_CLASSES);

		final List<String> names = new ArrayList<>();
		for (final Path source : sources)
		{
			names.add(source.getFileName().toString());
		}
		for (final String must : MUST_SCAN)
		{
			assertTrue(must + " was not among the " + names.size() + " files scanned under "
				+ root.toAbsolutePath() + " - this test cannot vouch for a file it never read, and that one"
				+ " does file I/O", names.contains(must));
		}
	}

	@Test
	public void theStoreAndTheBridgeImportFilepath() throws IOException
	{
		final Path root = packageRoot();
		final Pattern imported = Pattern.compile("import\\s+net\\.runelite\\.client\\.util\\.Filepath\\s*;");
		for (final String name : Arrays.asList("PriceStore.java", "BpmCommands.java"))
		{
			final String code = NoReflectionRuleTest.blank(
				new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8));
			assertTrue(name + " does not import net.runelite.client.util.Filepath - either the port was undone"
				+ " or its file work moved somewhere this test does not look",
				imported.matcher(code).find());
		}
	}

	/**
	 * How many compiled classes (nested ones too) the package has in the main output, at any depth: the class-path root
	 * is found through {@code PriceStore}'s own code source, as the kit's rule test finds it by package name.
	 */
	private static int compiledClasses() throws Exception
	{
		final Path root = Paths.get(PriceStore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		final String prefix = BankRuleFloorsTest.class.getPackage().getName().replace('.', '/') + "/";
		try (Stream<Path> walk = Files.walk(root))
		{
			return (int) walk.filter(p -> p.toString().endsWith(".class"))
				.filter(p -> root.relativize(p).toString().replace('\\', '/').startsWith(prefix))
				.count();
		}
	}

	/** {@code src/main/java/<this test's package>}, as the kit's rule tests find it. */
	private static Path packageRoot()
	{
		final String name = BankRuleFloorsTest.class.getName();
		final String pkg = name.substring(0, name.lastIndexOf('.'));
		final Path root = Paths.get("src", "main", "java").resolve(Paths.get("", pkg.split("\\.")));
		assertTrue("wrong working directory - no package at " + root.toAbsolutePath(), Files.isDirectory(root));
		return root;
	}

	/** Every {@code .java} file under {@code root}, at any depth, in a stable order. */
	private static List<Path> sourcesUnder(final Path root) throws IOException
	{
		try (Stream<Path> walk = Files.walk(root))
		{
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
	}
}
