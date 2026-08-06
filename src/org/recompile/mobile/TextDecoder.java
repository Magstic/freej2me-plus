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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/** Eagerly decodes text using strict encoding rules and closes the input stream. */
public final class TextDecoder
{
	private TextDecoder() { }

	public static BufferedReader open(InputStream input, String encoding) throws IOException
	{
		return open(input, encoding, -1);
	}

	public static BufferedReader open(InputStream input, String encoding, int maximumSize) throws IOException
	{
		byte[] data = readAll(input, maximumSize);
		return new BufferedReader(new StringReader(decode(data, 0, data.length, encoding)));
	}

	public static BufferedReader openDescriptor(InputStream input, String encoding, int maximumSize) throws IOException
	{
		byte[] data = readAll(input, maximumSize);
		if(!"ISO_8859_1".equals(encoding))
		{
			return new BufferedReader(new StringReader(decode(data, 0, data.length, encoding)));
		}

		String text;

		if(hasPrefix(data, 0xEF, 0xBB, 0xBF))
		{
			text = decode(data, 3, data.length - 3, "UTF-8");
		}
		else
		{
			try
			{
				text = decode(data, 0, data.length, "UTF-8");
			}
			catch(CharacterCodingException invalidUtf8)
			{
				text = decode(data, 0, data.length, encoding);
			}
		}

		return new BufferedReader(new StringReader(text));
	}

	private static byte[] readAll(InputStream input, int maximumSize) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int total = 0;

		try
		{
			int count;
			while((count = input.read(buffer)) >= 0)
			{
				if(count == 0) { continue; }
				total += count;
				if(maximumSize >= 0 && total > maximumSize)
				{
					throw new IOException("Text exceeds " + maximumSize + " bytes");
				}
				output.write(buffer, 0, count);
			}
		}
		finally { input.close(); }

		return output.toByteArray();
	}

	private static String decode(
		byte[] data,
		int offset,
		int length,
		String encoding) throws IOException
	{
		CharsetDecoder decoder;
		try
		{
			decoder = Charset.forName(encoding).newDecoder();
		}
		catch(Exception e)
		{
			throw new IOException("Unsupported text encoding: " + encoding, e);
		}

		decoder.onMalformedInput(CodingErrorAction.REPORT);
		decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
		return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString();
	}

	private static boolean hasPrefix(byte[] data, int first, int second, int third)
	{
		return data.length >= 3 &&
			(data[0] & 0xFF) == first &&
			(data[1] & 0xFF) == second &&
			(data[2] & 0xFF) == third;
	}
}
