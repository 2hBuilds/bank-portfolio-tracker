package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Part of the 2hBuilds kit. The kit's files are changed in the kit (2hBuilds-kit) and synced into the plugin
 * with sync-kit.py, never edited here. The sync writes kit-manifest.json at the plugin's root with the sha-256
 * of every file it copied, as written; this test hashes each one again and fails naming every file that no
 * longer matches, itself included.
 */
public class KitSyncTest
{
	private static final String MANIFEST = "kit-manifest.json";

	@Test
	public void everyKitFileIsExactlyAsTheKitSentIt() throws IOException
	{
		// Gradle runs a plugin's tests with the plugin's folder as the working directory.
		Path root = Paths.get("").toAbsolutePath();
		Path manifestFile = root.resolve(MANIFEST);
		Assert.assertTrue(MANIFEST + " is missing from " + root + " - run sync-kit.py from the kit",
			Files.isRegularFile(manifestFile));

		JsonObject manifest;
		try (Reader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8))
		{
			manifest = new Gson().fromJson(reader, JsonObject.class);
		}
		Assert.assertNotNull(MANIFEST + " is empty", manifest);
		Assert.assertTrue(MANIFEST + " has no files list", manifest.has("files") && manifest.get("files").isJsonObject());

		JsonObject files = manifest.getAsJsonObject("files");
		Assert.assertFalse(MANIFEST + " lists no files", files.entrySet().isEmpty());

		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : files.entrySet())
		{
			String relative = entry.getKey();
			Path file = root.resolve(relative);
			if (!Files.isRegularFile(file))
			{
				problems.add(relative + " - missing from the plugin");
			}
			else if (!sha256(Files.readAllBytes(file)).equalsIgnoreCase(entry.getValue().getAsString()))
			{
				problems.add(relative + " - edited in the plugin - change it in the kit and sync");
			}
		}

		if (!problems.isEmpty())
		{
			Assert.fail("Kit files that are not as the kit sent them:\n  " + String.join("\n  ", problems));
		}
	}

	private static String sha256(byte[] bytes)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder hex = new StringBuilder();
			for (byte b : digest)
			{
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
