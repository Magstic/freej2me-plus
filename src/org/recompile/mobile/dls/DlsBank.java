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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parsed DLS bank aggregate and instrument lookup rules. */
public final class DlsBank
{
	final List<Wave> waves;
	private final Map<Integer, Instrument> bySelector;
	private final int[] percussionKeyAliases;
	private final Map<Integer, Integer> programAliasSelectors;

	DlsBank(String sourceName,
		List<Instrument> instruments, List<Wave> waves, int[] percussionKeyAliases,
		Map<Integer, Integer> programAliasSelectors)
	{
		this.waves = Collections.unmodifiableList(new ArrayList<Wave>(waves));
		this.percussionKeyAliases = percussionKeyAliases == null ? null : percussionKeyAliases.clone();
		Map<Integer, Integer> aliasSelectors = new HashMap<Integer, Integer>();
		if(programAliasSelectors != null)
		{
			aliasSelectors.putAll(programAliasSelectors);
		}
		this.programAliasSelectors = Collections.unmodifiableMap(aliasSelectors);
		this.bySelector = new HashMap<Integer, Instrument>();
		for(Instrument instrument : instruments)
		{
			int selector = SynthesisSupport.selector(instrument.bankMsb, instrument.bankLsb, instrument.program);
			if(bySelector.put(selector, instrument) != null)
			{
				throw new IllegalArgumentException(sourceName + ": duplicate instrument selector 0x"
					+ Integer.toHexString(selector));
			}
			for(Region region : instrument.regions)
			{
				if(region.tableIndex < 0 || region.tableIndex >= this.waves.size())
				{
					throw new IllegalArgumentException(sourceName + ": region references missing wave "
						+ region.tableIndex);
				}
				Wave wave = this.waves.get(region.tableIndex);
				if(wave.channels != 1 && wave.channels != 2)
				{
					throw new IllegalArgumentException(sourceName + ": region references unsupported "
						+ wave.channels + "-channel wave " + region.tableIndex);
				}
				if(!region.sample.present)
				{
					region.sample = wave.sample;
				}
			}
		}
	}

	private Instrument instrumentFor(int bankMsb, int bankLsb, int program)
	{
		int sourceSelector = SynthesisSupport.selector(bankMsb, bankLsb, program);
		Instrument instrument = bySelector.get(sourceSelector);
		if(instrument != null)
		{
			return instrument;
		}
		Integer aliasSelector = programAliasSelectors.get(sourceSelector);
		return aliasSelector == null ? null : bySelector.get(aliasSelector);
	}

	int percussionKeyAliasFor(int bankSelector, int key)
	{
		if((bankSelector & 0x3F80) != (120 << 7) || percussionKeyAliases == null)
		{
			return key & 0x7F;
		}
		return percussionKeyAliases[key & 0x7F];
	}

	boolean hasPercussionKeyAliasesFor(int bankSelector)
	{
		return (bankSelector & 0x3F80) == (120 << 7) && percussionKeyAliases != null;
	}

	Instrument midiInstrument(int bankSelector, int program)
	{
		int bankMsb = (bankSelector >> 7) & 0x7F;
		int bankLsb = bankSelector & 0x7F;
		Instrument instrument = instrumentFor(bankMsb, bankLsb, program);
		if(instrument != null)
		{
			return instrument;
		}
		if(bankLsb != 0 && (bankMsb == 120 || bankMsb == 121))
		{
			instrument = instrumentFor(bankMsb, 0, program);
			if(instrument != null)
			{
				return instrument;
			}
		}
		return bankMsb == 120 ? instrumentFor(120, 0, 0) : null;
	}
}
