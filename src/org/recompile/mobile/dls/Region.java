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
package org.recompile.mobile.dls;

/** Playable key and velocity region mapped to a wave sample. */
final class Region
{
	static final int OPTION_SELF_EXCLUSIVE = 0x10;

	int keyLow = 0;
	int keyHigh = 127;
	int velocityLow = 0;
	int velocityHigh = 127;
	int options = 0;
	int keyGroup = 0;
	int tableIndex = -1;
	int index = 0;
	Articulation articulation;
	SampleInfo sample = new SampleInfo();
	boolean ownsArticulation;

	Region(Articulation inheritedArticulation)
	{
		this.articulation = inheritedArticulation;
	}

	boolean contains(int key, int velocity)
	{
		return key >= keyLow && key <= keyHigh && velocity >= velocityLow && velocity <= velocityHigh;
	}
}
