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
import java.util.List;

/** DLS articulation state and runtime connection list. */
final class Articulation
{
	int lfoFrequency = 200000;
	int lfoStartDelay = 10000;
	int vibratoFrequency = 200000;
	int vibratoStartDelay = 10000;
	int eg1Delay = 0;
	int eg1Attack = 0;
	int eg1Hold = 0;
	int eg1Decay = 0;
	int eg1Sustain = 0x10000;
	int eg1Release = 0;
	int eg2Delay = 0;
	int eg2Attack = 0;
	int eg2Hold = 0;
	int eg2Decay = 0;
	int eg2Sustain = 0x10000;
	int eg2Release = 0;
	int pitch = 0;
	int pan = 0;
	int filterCutoff = SynthesisSupport.FILTER_DISABLED_CUTOFF;
	int filterResonance = 0;
	final List<Connection> runtimeConnections = new ArrayList<Connection>();
	boolean defaultsAdded;

	void apply(Connection c)
	{
		// Reverb and chorus destinations are not rendered.
		if(c.destination == 0x80 || c.destination == 0x81) { return; }
		if(c.source != 0)
		{
			if(c.source != 4 && c.source != 7 && c.source != 8 && c.source != 0x102
				&& (c.source < 0xC6 || c.source > 0xCF))
			{
				runtimeConnections.add(c);
			}
			return;
		}
		if(c.destination == 3)
		{
			pitch = c.scale / 100;
		}
		else if(c.destination == 4)
		{
			pan = c.scale / 500;
		}
		else if(c.destination == 0x104)
		{
			lfoFrequency = SynthesisSupport.plusLfoPeriod(c.scale);
		}
		else if(c.destination == 0x105)
		{
			lfoStartDelay = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x114)
		{
			vibratoFrequency = SynthesisSupport.plusLfoPeriod(c.scale);
		}
		else if(c.destination == 0x115)
		{
			vibratoStartDelay = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x20B)
		{
			eg1Delay = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x206)
		{
			eg1Attack = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x20C)
		{
			eg1Hold = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x207)
		{
			eg1Decay = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x209)
		{
			eg1Release = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x20A)
		{
			eg1Sustain = c.scale / 1000;
		}
		else if(c.destination == 0x30F)
		{
			eg2Delay = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x30A)
		{
			eg2Attack = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x310)
		{
			eg2Hold = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x30B)
		{
			eg2Decay = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x30D)
		{
			eg2Release = SynthesisSupport.timecentToMicros(c.scale);
		}
		else if(c.destination == 0x30E)
		{
			eg2Sustain = c.scale / 1000;
		}
		else if(c.destination == 0x500)
		{
			filterCutoff = c.scale == Integer.MAX_VALUE ? SynthesisSupport.FILTER_DISABLED_CUTOFF : c.scale / 100;
			if(filterCutoff == SynthesisSupport.FILTER_DISABLED_CUTOFF)
			{
				filterResonance = 0;
			}
		}
		else if(c.destination == 0x501)
		{
			filterResonance = c.scale / 10;
		}
	}

	void addDefaultConnections()
	{
		if(defaultsAdded)
		{
			return;
		}
		defaultsAdded = true;
		addDefaultIfMissing(new Connection(3, 0, 3, 0x0000, 838860800));
		addDefaultIfMissing(new Connection(2, 0, 1, 0x8400, -31457280));
		addDefaultIfMissing(new Connection(6, 0x100, 3, 0x4000, 838860800));
		addDefaultIfMissing(new Connection(0x87, 0, 1, 0x8400, -62914560));
		addDefaultIfMissing(new Connection(0x8B, 0, 1, 0x8400, -62914560));
		addDefaultIfMissing(new Connection(0x101, 0, 3, 0x4000, 6553600));
		addDefaultIfMissing(new Connection(0x8A, 0, 4, 0x4000, 33292288));
	}

	void addDefaultIfMissing(Connection candidate)
	{
		for(Connection existing : runtimeConnections)
		{
			if(existing.source == candidate.source && existing.control == candidate.control
				&& existing.destination == candidate.destination)
			{
				return;
			}
		}
		apply(candidate);
	}
}
