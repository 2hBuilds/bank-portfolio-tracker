package com.bankpricemovement;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The contract's rule for addendum AU (phase 0 contract section 0; plan 7.2 item 13): "No member is added to
 * {@code src/main} whose only caller is a test." The final review found seven that were (R1): three card colour
 * helpers whose last main caller the History card's rewrite removed, and four accessors of the History model
 * the contract had frozen before anything called them. This test holds the package to the rule from here on.
 *
 * <p>It reads the COMPILED main classes, not the source: every method a main class calls is named in that class's
 * constant pool (a {@code Methodref}, an {@code InterfaceMethodref}, or a method handle for a method reference), so a
 * method no main class names has no caller in the shipped jar. A source search cannot tell {@code series.merge(...)}
 * from {@code map.merge(...)}; the constant pool names the owner.
 *
 * <p>Not counted as test-only: a method that overrides or implements one it inherits (the framework calls
 * {@code equals}, {@code paintComponent}, {@code getPreferredSize}), a method RuneLite reaches by annotation
 * ({@code @Subscribe}, {@code @Provides}, the config interface's items), an enum's {@code valueOf} (RuneLite's config
 * parses stored names through it), and a lambda body. Everything that predates AU and breaks the rule is listed by
 * name in {@link #BEFORE_AU}, so a NEW one fails and an old one taken out of use fails too, until it leaves the list.
 */
public class NoTestOnlyMembersRuleTest
{
	/**
	 * Members that break the rule and were in {@code src/main} before addendum AU (every one declared at 7898eb1,
	 * checked member by member against that commit's sources): mostly the panel's package-private accessors that its
	 * tests read, and addendum AS's hooks. Left alone by the AU round, each named so that the list can only shrink.
	 * {@code Owner#name}, the owner by its simple binary name; an overloaded name is listed once.
	 */
	private static final Set<String> BEFORE_AU = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
		"BankPriceMovementPanel#bandTarget", "BankPriceMovementPanel#captionLabel", "BankPriceMovementPanel#captionRow",
		"BankPriceMovementPanel#card", "BankPriceMovementPanel#chipRow", "BankPriceMovementPanel#clearBandButton",
		"BankPriceMovementPanel#clearBandShowing", "BankPriceMovementPanel#clearBoundsLabel",
		"BankPriceMovementPanel#controlRow", "BankPriceMovementPanel#countCashItem",
		"BankPriceMovementPanel#countInventoryItem", "BankPriceMovementPanel#countUntradeablesItem",
		"BankPriceMovementPanel#deltaLabel", "BankPriceMovementPanel#filter", "BankPriceMovementPanel#fold",
		"BankPriceMovementPanel#foldOpen", "BankPriceMovementPanel#footnoteLabel", "BankPriceMovementPanel#gearLabel",
		"BankPriceMovementPanel#glowTimer", "BankPriceMovementPanel#gutter", "BankPriceMovementPanel#header",
		"BankPriceMovementPanel#hero", "BankPriceMovementPanel#heroMenu", "BankPriceMovementPanel#listThenDay",
		"BankPriceMovementPanel#listView", "BankPriceMovementPanel#livePricesItem", "BankPriceMovementPanel#maxField",
		"BankPriceMovementPanel#minField", "BankPriceMovementPanel#moveLine", "BankPriceMovementPanel#okButton",
		"BankPriceMovementPanel#okRow", "BankPriceMovementPanel#pctLabel", "BankPriceMovementPanel#presetCell",
		"BankPriceMovementPanel#presetField", "BankPriceMovementPanel#presetRow", "BankPriceMovementPanel#problemLabel",
		"BankPriceMovementPanel#problemShowing", "BankPriceMovementPanel#provenanceText",
		"BankPriceMovementPanel#rebuilds", "BankPriceMovementPanel#refreshAcknowledging",
		"BankPriceMovementPanel#refreshLabel", "BankPriceMovementPanel#refreshTimersRunning",
		"BankPriceMovementPanel#resetPresetsButton", "BankPriceMovementPanel#rowPanels",
		"BankPriceMovementPanel#rowsColumn", "BankPriceMovementPanel#scrollPane", "BankPriceMovementPanel#setClock",
		"BankPriceMovementPanel#setGlowClock", "BankPriceMovementPanel#setSort", "BankPriceMovementPanel#showGpItem",
		"BankPriceMovementPanel#showHoverTextItem", "BankPriceMovementPanel#showMoreLabel",
		"BankPriceMovementPanel#showPctItem", "BankPriceMovementPanel#showValueItem", "BankPriceMovementPanel#sortButton",
		"BankPriceMovementPanel#status", "BankPriceMovementPanel#stopped", "BankPriceMovementPanel#stripHolder",
		"BankPriceMovementPanel#totalLabel", "BankPriceMovementPanel#totalRow", "BankPriceMovementPanel#triangleLabel",
		"BankPriceMovementPanel#upToDateShowing", "BankPriceMovementPanel#updateLabel",
		"BankPriceMovementPanel#updateTooltip", "BankPriceMovementPanel#windowChip", "BankReader$Carried#isEmpty",
		"BankSnapshot#isEmpty", "BpmCommands#writeShot", "GuidePriceClient#parseGuideTable", "GuideSnapshot#get",
		"GuideSnapshot#revisionSeconds", "GuideSnapshot#size", "HeroVisibility#any", "MovementMath#formatPct",
		"MovementMath#formatSince", "MovementRow#asLive", "MovementRowPanel#changeColor", "MovementRowPanel#changeText",
		"MovementRowPanel#detailBuilds", "MovementRowPanel#gpText", "MovementRowPanel#hovered", "MovementRowPanel#icon",
		"MovementRowPanel#iconLabel", "MovementRowPanel#isParts", "MovementRowPanel#nameText",
		"MovementRowPanel#priceText", "MovementRowPanel#railColor", "MovementRowPanel#row", "MovementRowPanel#tooltip",
		"MovementRowPanel#tooltipBuilds", "MovementRowPanel#tooltipHtml", "PortfolioMath#summarise",
		"PortfolioSummary#liveRows", "PortfolioSummary#moves", "PortfolioSummary#valueStacks", "PriceMap#isEmpty",
		"PriceMap#points", "PricePoint#isEmpty", "PriceService#isVisible", "PriceService#isWikiEnabled",
		"PriceService#options", "PriceService#pickBaseline", "PriceService#refreshNow", "PriceService#revisionIndex",
		"PriceService#setTradedClient", "PriceService$Status#anchorDegraded", "PriceService$Status#baselineRevId",
		"PriceService$Status#baselineRevisionSeconds", "PriceService$Status$LiveStatus#alchRows",
		"PriceService$Status$LiveStatus#fetchedAtMillis", "PriceService$Status$LiveStatus#guideRows",
		"PriceService$Status$LiveStatus#latestItems", "PriceService$Status$LiveStatus#liveDay",
		"TradedPriceClient#isEnabled", "TradedPriceClient#setEnabled", "Widgets#column", "Widgets#dotIcon", "Widgets#fit",
		"Widgets#fitName", "Widgets$PlaceholderField#placeholder", "WindowMove#window"
	)));

	@Test
	public void everyMainMethodHasAMainCaller() throws Exception
	{
		final Path root = mainClasses();
		final List<Path> classFiles;
		try (Stream<Path> walk = Files.walk(root))
		{
			classFiles = walk.filter(p -> p.toString().endsWith(".class"))
				.filter(p -> root.relativize(p).toString().replace('\\', '/').startsWith("com/bankpricemovement/"))
				.collect(Collectors.toList());
		}
		assertTrue("the walk found the package's compiled classes: " + classFiles.size(), classFiles.size() > 30);

		final Set<String> referenced = new HashSet<>();
		for (final Path file : classFiles)
		{
			try (InputStream in = Files.newInputStream(file))
			{
				referenced.addAll(methodRefs(in));
			}
		}

		final Set<String> testOnly = new TreeSet<>();
		for (final Path file : classFiles)
		{
			final String binary = root.relativize(file).toString().replace('\\', '/').replaceAll("\\.class$", "")
				.replace('/', '.');
			final Class<?> type = Class.forName(binary, false, getClass().getClassLoader());
			if (type.isInterface() || type.isAnnotation() || type.isSynthetic())
			{
				continue;
			}
			for (final Method method : type.getDeclaredMethods())
			{
				if (exempt(type, method))
				{
					continue;
				}
				final String internal = type.getName().replace('.', '/');
				if (!referenced.contains(internal + "." + method.getName() + descriptor(method)))
				{
					testOnly.add(simpleBinary(type) + "#" + method.getName());
				}
			}
		}

		final Set<String> fresh = new TreeSet<>(testOnly);
		fresh.removeAll(BEFORE_AU);
		final Set<String> gone = new TreeSet<>(BEFORE_AU);
		gone.removeAll(testOnly);
		assertEquals("members with no caller in src/main (only a test calls them) - give each a main caller or delete it",
			Collections.emptySet(), fresh);
		assertEquals("members listed as pre-AU test-only that now have a main caller or are gone - take them off the list",
			Collections.emptySet(), gone);
	}

	/** Whether the rule does not apply to this method (see the class comment). */
	private static boolean exempt(final Class<?> type, final Method method)
	{
		if (method.isSynthetic() || method.isBridge() || Modifier.isPrivate(method.getModifiers())
			|| method.getName().startsWith("lambda$"))
		{
			return true;
		}
		if (method.getDeclaredAnnotations().length > 0 && Arrays.stream(method.getDeclaredAnnotations())
			.anyMatch(a -> !a.annotationType().getName().startsWith("javax.annotation.")))
		{
			// @Subscribe, @Provides and the like: RuneLite calls them. (@Nullable does not make a caller.)
			return true;
		}
		if (type.isEnum() && ("valueOf".equals(method.getName()) || "values".equals(method.getName())))
		{
			return true;
		}
		return overrides(type, method);
	}

	/** Whether a superclass or an interface of {@code type} declares a method this one overrides or implements. */
	private static boolean overrides(final Class<?> type, final Method method)
	{
		final List<Class<?>> above = new ArrayList<>();
		for (Class<?> c = type.getSuperclass(); c != null; c = c.getSuperclass())
		{
			above.add(c);
		}
		final List<Class<?>> pending = new ArrayList<>(Arrays.asList(type.getInterfaces()));
		for (final Class<?> c : new ArrayList<>(above))
		{
			pending.addAll(Arrays.asList(c.getInterfaces()));
		}
		while (!pending.isEmpty())
		{
			final Class<?> c = pending.remove(pending.size() - 1);
			above.add(c);
			pending.addAll(Arrays.asList(c.getInterfaces()));
		}
		for (final Class<?> c : above)
		{
			try
			{
				final Method inherited = c.getDeclaredMethod(method.getName(), method.getParameterTypes());
				if (!Modifier.isPrivate(inherited.getModifiers()) && !Modifier.isStatic(inherited.getModifiers()))
				{
					return true;
				}
			}
			catch (NoSuchMethodException e)
			{
				// not declared there
			}
		}
		return false;
	}

	private static String simpleBinary(final Class<?> type)
	{
		final String name = type.getName();
		return name.substring(name.lastIndexOf('.') + 1);
	}

	/** The JVM descriptor of a method: {@code (JLjava/lang/String;)Z}. */
	static String descriptor(final Method method)
	{
		final StringBuilder out = new StringBuilder("(");
		for (final Class<?> p : method.getParameterTypes())
		{
			out.append(descriptor(p));
		}
		return out.append(')').append(descriptor(method.getReturnType())).toString();
	}

	private static String descriptor(final Class<?> type)
	{
		if (type.isArray())
		{
			return type.getName().replace('.', '/');
		}
		if (type.isPrimitive())
		{
			if (type == void.class)
			{
				return "V";
			}
			if (type == boolean.class)
			{
				return "Z";
			}
			if (type == long.class)
			{
				return "J";
			}
			return String.valueOf(Character.toUpperCase(type.getName().charAt(0)));
		}
		return "L" + type.getName().replace('.', '/') + ";";
	}

	/**
	 * Every method a class file names in its constant pool, as {@code owner.name(descriptor)} - the Methodref and
	 * InterfaceMethodref entries, which is where every call and every method reference (through its method handle)
	 * points. JVMS 4.4.
	 */
	static Set<String> methodRefs(final InputStream bytes) throws IOException
	{
		final DataInputStream in = new DataInputStream(bytes);
		if (in.readInt() != 0xCAFEBABE)
		{
			throw new IOException("not a class file");
		}
		in.readUnsignedShort();
		in.readUnsignedShort();
		final int count = in.readUnsignedShort();
		final String[] utf8 = new String[count];
		final int[] classNames = new int[count];
		final int[][] pairs = new int[count][];
		final boolean[] isMethod = new boolean[count];
		for (int i = 1; i < count; i++)
		{
			final int tag = in.readUnsignedByte();
			switch (tag)
			{
				case 1:
					utf8[i] = in.readUTF();
					break;
				case 7:
					classNames[i] = in.readUnsignedShort();
					break;
				case 8:
				case 16:
				case 19:
				case 20:
					in.readUnsignedShort();
					break;
				case 3:
				case 4:
					in.readInt();
					break;
				case 5:
				case 6:
					in.readLong();
					i++;
					break;
				case 10:
				case 11:
					pairs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
					isMethod[i] = true;
					break;
				case 9:
				case 12:
				case 17:
				case 18:
					pairs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
					break;
				case 15:
					in.readUnsignedByte();
					in.readUnsignedShort();
					break;
				default:
					throw new IOException("constant pool tag " + tag + " at " + i);
			}
		}
		final Set<String> refs = new HashSet<>();
		for (int i = 1; i < count; i++)
		{
			if (isMethod[i])
			{
				final String owner = utf8[classNames[pairs[i][0]]];
				final int[] nameAndType = pairs[pairs[i][1]];
				refs.add(owner + "." + utf8[nameAndType[0]] + utf8[nameAndType[1]]);
			}
		}
		return refs;
	}

	/** {@code build/classes/java/main}, found through a main class's own code source. */
	private static Path mainClasses() throws URISyntaxException
	{
		return Paths.get(BankHistorySeries.class.getProtectionDomain().getCodeSource().getLocation().toURI());
	}
}
