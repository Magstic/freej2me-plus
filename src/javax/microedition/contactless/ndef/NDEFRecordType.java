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
package javax.microedition.contactless.ndef;

import org.recompile.mobile.Mobile;

public class NDEFRecordType 
{

    public static final int EMPTY = 0;
    public static final int EXTERNAL_RTD = 4;
    public static final int MIME = 2;
    public static final int NFC_FORUM_RTD = 1;
    public static final int UNKNOWN = 5;
    public static final int URI = 3;

    private final int format;
    private final String name;

    public NDEFRecordType(int format, String name) 
    {
        if (format < 0 || format > 5) { throw new IllegalArgumentException(); }
        if ((format == EMPTY || format == UNKNOWN) && name != null) { throw new IllegalArgumentException(); }
        this.format = format;
        this.name = name != null ? name : null;
    }

    @Override
    public boolean equals(Object recordType) 
    {
        if (!(recordType instanceof NDEFRecordType)) { return false; }
        NDEFRecordType other = (NDEFRecordType) recordType;
        
        return true; 
    }

    public int getFormat() { return format; }

    public String getName() { return name; }

    public byte[] getNameAsBytes() { return name != null ? Mobile.encodeString(name) : null; }

    @Override
    public int hashCode() 
    {
        return format * 31 + (name != null ? name.hashCode() : 0);
    }
}