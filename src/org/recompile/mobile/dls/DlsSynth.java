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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Loads DLS banks and creates realtime MIDI receivers backed by the DLS renderer. */
public final class DlsSynth
{
	private DlsSynth() { }

	public static DlsBank load(File file) throws IOException
	{
		FileInputStream in = new FileInputStream(file);
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 1024 * 1024));
			byte[] buf = new byte[8192];
			int read;
			while((read = in.read(buf)) >= 0)
			{
				if(read > 0) { out.write(buf, 0, read); }
			}
			return DlsParser.parse(out.toByteArray(), file.getPath());
		}
		finally { in.close(); }
	}

	public static Receiver createReceiver(DlsBank bank, Sequence sequence) throws MidiUnavailableException
	{
		return new DlsReceiver(bank, childTailInput(sequence));
	}

	private static int childTailInput(Sequence sequence)
	{
		if(sequence == null || sequence.getResolution() <= 0) { return 1; }

		List<SequenceEvent> events = new ArrayList<SequenceEvent>();
		Track[] tracks = sequence.getTracks();
		int order = 0;
		for(int track = 0; track < tracks.length; track++)
		{
			for(int i = 0; i < tracks[track].size(); i++)
			{
				javax.sound.midi.MidiEvent event = tracks[track].get(i);
				MidiMessage message = event.getMessage();
				if(message instanceof ShortMessage)
				{
					ShortMessage sm = (ShortMessage) message;
					int command = sm.getCommand();
					if(command >= 0x80 && command <= 0xE0)
					{
						events.add(new SequenceEvent(event.getTick(), track, order++, command,
								sm.getChannel(), sm.getData1(), sm.getData2()));
					}
				}
			}
		}
		Collections.sort(events, new Comparator<SequenceEvent>()
		{
			public int compare(SequenceEvent a, SequenceEvent b)
			{
				if(a.tick < b.tick) { return -1; }
				if(a.tick > b.tick) { return 1; }
				if(a.track != b.track) { return a.track - b.track; }
				return a.order - b.order;
			}
		});

		int[] channelWeight = new int[16];
		int[] volume14 = new int[16];
		int[] expression14 = new int[16];
		for(int ch = 0; ch < 16; ch++)
		{
			channelWeight[ch] = 61;
			volume14[ch] = 100 << 7;
			expression14[ch] = 127 << 7;
		}
		int[] recChannel = new int[65];
		int[] recKey = new int[65];
		int[] recWeight = new int[65];
		boolean[] melodicNoteChannel = new boolean[16];
		for(int i = 0; i < recChannel.length; i++) { recChannel[i] = -1; }

		// mobileBAE's type-5 preparation evaluates events at time zero, without note ageing.
		int maxMetric = 0;
		for(int e = 0; e < events.size(); e++)
		{
			SequenceEvent event = events.get(e);
			int ch = event.channel;
			if(event.command == 0x90 && event.data2 > 0)
			{
				if(ch != 9) { melodicNoteChannel[ch] = true; }
				int slot = -1;
				int firstFree = -1;
				for(int i = 0; i < recChannel.length; i++)
				{
					if(recChannel[i] == ch && recKey[i] == event.data1)
					{
						slot = i;
						break;
					}
					if(recChannel[i] < 0 && firstFree < 0) { firstFree = i; }
				}
				if(slot < 0) { slot = firstFree; }
				if(slot >= 0)
				{
					recChannel[slot] = ch;
					recKey[slot] = event.data1;
					recWeight[slot] = 100 * event.data2 * event.data2 / 16129;
					int metric = childTailMetric(channelWeight, recChannel, recWeight);
					if(metric > maxMetric) { maxMetric = metric; }
				}
			}
			else if(event.command == 0xB0)
			{
				int cc = event.data1;
				int value = event.data2;
				if(cc == 7 || cc == 11)
				{
					if(cc == 7) { volume14[ch] = value << 7; }
					else { expression14[ch] = value << 7; }
					int scaled = (volume14[ch] * expression14[ch]) / 16256;
					int squared = ((scaled & 0xFFFF) * (scaled & 0xFFFF)) / 16256;
					channelWeight[ch] = (100 * squared) / 16256;
					int metric = childTailMetric(channelWeight, recChannel, recWeight);
					if(metric > maxMetric) { maxMetric = metric; }
				}
				else if(cc == 120)
				{
					for(int i = 0; i < recChannel.length; i++)
					{
						if(recChannel[i] == ch) { recChannel[i] = -1; }
					}
				}
			}
		}
		int input = (maxMetric + 50) / 100;
		int channelFloor = 0;
		for(int i = 0; i < melodicNoteChannel.length; i++)
		{
			if(melodicNoteChannel[i]) { channelFloor++; }
		}
		if(input < channelFloor) { input = channelFloor; }
		return input < 1 ? 1 : input;
	}

	private static int childTailMetric(int[] channelWeight, int[] recChannel, int[] recWeight)
	{
		int total = 0;
		for(int i = 0; i < recChannel.length; i++)
		{
			if(recChannel[i] >= 0) { total += channelWeight[recChannel[i]] * recWeight[i] / 100; }
		}
		return total;
	}

	private static final class SequenceEvent
	{
		final long tick;
		final int track;
		final int order;
		final int command;
		final int channel;
		final int data1;
		final int data2;

		SequenceEvent(long tick, int track, int order, int command, int channel, int data1, int data2)
		{
			this.tick = tick;
			this.track = track;
			this.order = order;
			this.command = command;
			this.channel = channel;
			this.data1 = data1;
			this.data2 = data2;
		}
	}
}
