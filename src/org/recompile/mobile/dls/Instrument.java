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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** DLS instrument with bank selector, articulation, and regions. */
final class Instrument
{
	final int bankMsb;
	final int bankLsb;
	final int program;
	final List<Region> regions;

	Instrument(int rawBank, int rawInstrument, boolean rawMode, List<Region> regions)
	{
		int rawLsb = rawBank & 0x7F;
		int rawMsb = (rawBank >>> 8) & 0x7F;
		this.program = rawInstrument & 0x7F;
		boolean drum = (rawBank & 0x80000000) != 0;
		this.bankMsb = rawMode ? rawMsb : (drum ? 120 : 121);
		this.bankLsb = rawLsb;
		this.regions = Collections.unmodifiableList(new ArrayList<Region>(regions));
	}

	Region regionFor(int key, int velocity)
	{
		for(Region region : regions)
		{
			if(region.contains(key, velocity))
			{
				return region;
			}
		}
		return null;
	}
}
