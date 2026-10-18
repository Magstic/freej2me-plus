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
package com.nttdocomo.io;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;

public class FileDataInput implements java.io.DataInput, RandomAccessible 
{
    private final RandomAccessFile file;
    private final DataInputStream dataInput;
    private final BufferedReader reader;

    public FileDataInput(String filePath) throws IOException 
    {
        this.file = new RandomAccessFile(filePath, "r");
        this.dataInput = new DataInputStream(new FileInputStream(filePath));
        this.reader = new BufferedReader(new InputStreamReader(dataInput, "Shift_JIS"));
    }

    public void close() throws IOException 
    {
        reader.close();
        dataInput.close();
        file.close();
    }

    public long getPosition() throws IOException { return file.getFilePointer(); }

    public long getSize() throws IOException { return file.length(); }

    public boolean readBoolean() throws IOException { return dataInput.readBoolean(); }

    public byte readByte() throws IOException { return dataInput.readByte(); }

    public char readChar() throws IOException { return dataInput.readChar(); }

    public double readDouble() throws IOException { return dataInput.readDouble(); }

    public float readFloat() throws IOException { return dataInput.readFloat(); }

    public void readFully(byte[] b) throws IOException { dataInput.readFully(b); }

    public void readFully(byte[] b, int off, int len) throws IOException { dataInput.readFully(b, off, len); }

    public int readInt() throws IOException { return dataInput.readInt(); }

    public String readLine() throws IOException { return reader.readLine(); }

    public long readLong() throws IOException { return dataInput.readLong(); }

    public short readShort() throws IOException { return dataInput.readShort(); }

    public String readString() throws IOException { return readString(readUnsignedShort()); }

    public String readString(int bytes) throws IOException 
    {
        byte[] stringBytes = new byte[bytes];
        readFully(stringBytes);
        return new String(stringBytes, "Shift_JIS");
    }

    public int readUnsignedByte() throws IOException { return dataInput.readUnsignedByte(); }

    public int readUnsignedShort() throws IOException { return dataInput.readUnsignedShort(); }

    public String readUTF() throws IOException { return dataInput.readUTF(); }

    public void setPosition(long position) throws IOException { file.seek(position); }

    public void setPositionRelative(long position) throws IOException 
    {
        long newPos = getPosition() + position;
        setPosition(newPos);
    }

    public int skipBytes(int n) throws IOException { return dataInput.skipBytes(n); }
}