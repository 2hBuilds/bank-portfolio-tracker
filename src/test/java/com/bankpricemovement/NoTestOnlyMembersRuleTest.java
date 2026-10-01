package com.bankpricemovement;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
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
 * parses stored names through it), and a lambda body.
 *
 * <p><b>The tolerated set.</b> Everything that predates the rule and breaks it is listed by name in the optional
 * resource {@value #TOLERATED_RESOURCE} on the test classpath (the file's own first line says what it is), so a NEW
 * one fails and an old one taken out of use fails too, until it leaves the list. This plugin's list is
 * {@code src/test/resources/kit-rules/test-only-members.txt}: mostly the panel's package-private accessors that its
 * tests read, and addendum AS's hooks, every one declared at 7898eb1 and checked member by member against that
 * commit's sources, left alone by the AU round so that the list can only shrink. An absent file is an empty set.
 */
public class NoTestOnlyMembersRuleTest
{
	/**
	 * The optional classpath resource that lists the members a plugin tolerates: {@code kit-rules/test-only-members.txt}
	 * on the TEST classpath ({@code src/test/resources}). One {@code Owner#name} per line, the owner by its simple
	 * binary name, an overloaded name listed once; blank lines and lines starting with {@code #} are ignored. A plugin
	 * with no such file tolerates nothing, which is what a new plugin should start with. The list lives in a file and
	 * not in this class so that this class is the same in every 2hBuilds plugin, with nothing changed but its
	 * {@code package} line.
	 */
	private static final String TOLERATED_RESOURCE = "kit-rules/test-only-members.txt";

	@Test
	public void everyMainMethodHasAMainCaller() throws Exception
	{
		final Path root = mainClasses();
		final List<Path> classFiles;
		try (Stream<Path> walk = Files.walk(root))
		{
			classFiles = walk.filter(p -> p.toString().endsWith(".class"))
				.filter(p -> root.relativize(p).toString().replace('\\', '/').startsWith(packagePath() + "/"))
				.collect(Collectors.toList());
		}
		assertTrue("the walk found the package's compiled classes: " + classFiles.size(), classFiles.size() > 0);

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

		final Set<String> tolerated = tolerated();
		final Set<String> fresh = new TreeSet<>(testOnly);
		fresh.removeAll(tolerated);
		final Set<String> gone = new TreeSet<>(tolerated);
		gone.removeAll(testOnly);
		assertEquals("members with no caller in src/main (only a test calls them) - give each a main caller or delete it",
			Collections.emptySet(), fresh);
		assertEquals("members listed in " + TOLERATED_RESOURCE + " as test-only that now have a main caller or are gone - take them off the list",
			Collections.emptySet(), gone);
	}

	/**
	 * The members this plugin tolerates, read from {@link #TOLERATED_RESOURCE}: one entry per line, {@code #} comments and
	 * blank lines ignored, and an absent file is an empty set.
	 */
	private static Set<String> tolerated() throws IOException
	{
		final Set<String> out = new TreeSet<>();
		try (InputStream in = NoTestOnlyMembersRuleTest.class.getClassLoader().getResourceAsStream(TOLERATED_RESOURCE))
		{
			if (in == null)
			{
				return out;
			}
			final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				final String entry = line.trim();
				if (!entry.isEmpty() && !entry.startsWith("#"))
				{
					out.add(entry);
				}
			}
		}
		return out;
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

	/** This test's package as a folder, e.g. {@code com/zenbank}: the prefix every compiled class of the package has. */
	private static String packagePath()
	{
		final String name = NoTestOnlyMembersRuleTest.class.getName();
		return name.substring(0, name.lastIndexOf('.')).replace('.', '/');
	}

	/**
	 * The class-path root the plugin's MAIN classes are compiled into ({@code build/classes/java/main}): the root, among
	 * those that hold a folder for this package with compiled classes in it, that is not the one this test itself was
	 * compiled into. Found through the class loader and the package's own name, so it names no class of the plugin.
	 */
	private static Path mainClasses() throws Exception
	{
		final String folder = packagePath();
		final Path own = Paths.get(NoTestOnlyMembersRuleTest.class.getResource(
			NoTestOnlyMembersRuleTest.class.getSimpleName() + ".class").toURI());
		final Enumeration<URL> folders = NoTestOnlyMembersRuleTest.class.getClassLoader().getResources(folder);
		while (folders.hasMoreElements())
		{
			final URL url = folders.nextElement();
			if (!"file".equals(url.getProtocol()))
			{
				continue;
			}
			final Path dir = Paths.get(url.toURI());
			if (own.startsWith(dir))
			{
				continue;
			}
			try (Stream<Path> walk = Files.walk(dir))
			{
				if (walk.anyMatch(p -> p.toString().endsWith(".class")))
				{
					Path root = dir;
					for (int i = 0; i < folder.split("/").length; i++)
					{
						root = root.getParent();
					}
					return root;
				}
			}
		}
		throw new AssertionError("no compiled classes of " + folder + " outside this test's own output");
	}
}
