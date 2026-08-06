/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
package org.recompile.mobile;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Materializes frontend-provided content in a persistent, content-addressed cache.
 * Cached files are intentionally retained across launches.
 */
public final class ManagedContentCache
{
	private static final long MAX_CONTENT_SIZE = 256L * 1024L * 1024L;

	private static final File CACHE_DIRECTORY =
		new File("freej2me_system" + File.separator + "content_cache");

	private ManagedContentCache() { }

	public static String materialize(InputStream input, long contentSize) throws IOException
	{
		if(contentSize <= 0 || contentSize > MAX_CONTENT_SIZE)
		{
			throw new IOException("Invalid managed content size: " + contentSize);
		}
		if(!CACHE_DIRECTORY.isDirectory() &&
		   !CACHE_DIRECTORY.mkdirs() &&
		   !CACHE_DIRECTORY.isDirectory())
		{
			throw new IOException("Could not create managed content cache");
		}

		File temporary = File.createTempFile("incoming-", ".part", CACHE_DIRECTORY);
		FileOutputStream output = null;

		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			output = new FileOutputStream(temporary);

			byte[] buffer = new byte[32768];
			byte[] signature = new byte[3];
			int signatureLength = 0;
			long remaining = contentSize;

			while(remaining > 0)
			{
				int requested = (int)Math.min((long)buffer.length, remaining);
				int count = input.read(buffer, 0, requested);
				if(count < 0) { throw new EOFException("Content stream ended early"); }
				if(count == 0) { continue; }

				if(signatureLength < signature.length)
				{
					int copied = Math.min(signature.length - signatureLength, count);
					System.arraycopy(buffer, 0, signature, signatureLength, copied);
					signatureLength += copied;
				}

				output.write(buffer, 0, count);
				digest.update(buffer, 0, count);
				remaining -= count;
			}

			output.close();
			output = null;

			String extension = detectExtension(signature, signatureLength);
			byte[] contentDigest = digest.digest();
			File target = new File(CACHE_DIRECTORY, toHex(contentDigest) + extension);

			if(target.isFile() && hasContent(target, contentSize, contentDigest))
			{
				return target.toURI().toString();
			}

			Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

			return target.toURI().toString();
		}
		catch(NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
		finally
		{
			if(output != null)
			{
				try { output.close(); }
				catch(IOException ignored) { }
			}
			if(temporary.isFile() && !temporary.delete()) { temporary.deleteOnExit(); }
		}
	}

	private static boolean hasContent(File file, long expectedSize, byte[] expectedDigest) throws IOException
	{
		if(file.length() != expectedSize) { return false; }

		FileInputStream input = new FileInputStream(file);
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[32768];
			int count;
			while((count = input.read(buffer)) >= 0)
			{
				if(count > 0) { digest.update(buffer, 0, count); }
			}
			return MessageDigest.isEqual(expectedDigest, digest.digest());
		}
		catch(NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
		finally { input.close(); }
	}

	private static String detectExtension(byte[] signature, int length) throws IOException
	{
		if(length >= 3 && signature[0] == 'K' && signature[1] == 'J' && signature[2] == 'X')
		{
			return ".kjx";
		}
		if(length >= 2 && signature[0] == 'P' && signature[1] == 'K')
		{
			return ".jar";
		}
		throw new IOException("Content is not a JAR or KJX file");
	}

	private static String toHex(byte[] bytes)
	{
		char[] digits = "0123456789abcdef".toCharArray();
		char[] result = new char[bytes.length * 2];
		for(int i = 0; i < bytes.length; i++)
		{
			int value = bytes[i] & 0xFF;
			result[i * 2] = digits[value >>> 4];
			result[i * 2 + 1] = digits[value & 0x0F];
		}
		return new String(result);
	}
}
