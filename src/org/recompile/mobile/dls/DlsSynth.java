/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
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

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Loads DLS banks and creates realtime MIDI receivers backed by the DLS renderer. */
public final class DlsSynth
{
	public static final int DEFAULT_RATE = 22050;
	public static final int DEFAULT_VOICES = 256;

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
		finally { try { in.close(); } catch(IOException ignored) { } }
	}

	public static Receiver createReceiver(DlsBank bank, int sampleRate, int voices, boolean reverb, boolean chorus) throws MidiUnavailableException
	{
		return createReceiver(bank, sampleRate, voices, reverb, chorus, 1);
	}

	public static Receiver createReceiver(DlsBank bank, int sampleRate, int voices, boolean reverb, boolean chorus, int childTailInput) throws MidiUnavailableException
	{
		return new DlsReceiver(bank, clampRate(sampleRate), clampVoices(voices), reverb, chorus, childTailInput);
	}

	public static int childTailInput(Sequence sequence)
	{
		if(sequence == null) { return 1; }
		int division = sequence.getResolution();
		if(division <= 0) { return 1; }

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
								sm.getChannel(), sm.getData1(), sm.getData2(), -1));
					}
				}
				else if(message instanceof MetaMessage)
				{
					MetaMessage meta = (MetaMessage) message;
					byte[] data = meta.getData();
					if(meta.getType() == 0x51 && data.length == 3)
					{
						int tempo = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
						events.add(new SequenceEvent(event.getTick(), track, order++, -1, -1, 0, 0, clampTempo(tempo)));
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

		int[] channelType = new int[16];
		int[] channelWeight = new int[16];
		int[] volume14 = new int[16];
		int[] expression14 = new int[16];
		boolean[] cc4Held = new boolean[16];
		for(int ch = 0; ch < 16; ch++)
		{
			channelType[ch] = ch == 9 ? 2 : 1;
			channelWeight[ch] = 61;
			volume14[ch] = 100 << 7;
			expression14[ch] = 127 << 7;
		}
		int[] recChannel = new int[65];
		int[] recKey = new int[65];
		int[] recType = new int[65];
		int[] recWeight = new int[65];
		int[] recFlags = new int[65];
		int[] recTime = new int[65];
		boolean[] melodicNoteChannel = new boolean[16];
		for(int i = 0; i < recChannel.length; i++) { recChannel[i] = -1; }

		int active = 0;
		int maxMetric = 0;
		long tick = 0;
		long micros = 0;
		int tempo = 500000;
		for(int e = 0; e < events.size(); e++)
		{
			SequenceEvent event = events.get(e);
			if(sequence.getDivisionType() == Sequence.PPQ)
			{
				micros += (event.tick - tick) * tempo / division;
			}
			else
			{
				micros = (long) (event.tick * 1000000.0 / (sequence.getDivisionType() * division));
			}
			tick = event.tick;
			if(event.tempo > 0)
			{
				tempo = event.tempo;
				continue;
			}
			if(event.channel < 0) { continue; }

			int ms = (int) Math.min(Integer.MAX_VALUE, micros / 1000L);
			int high = event.command & 0xF0;
			int ch = event.channel & 0x0F;
			if(high == 0x90 && event.data2 > 0)
			{
				if(ch != 9) { melodicNoteChannel[ch] = true; }
				int slot = -1;
				int firstFree = -1;
				for(int i = 0; i < recChannel.length; i++)
				{
					if(recChannel[i] == ch && recKey[i] == event.data1)
					{
						slot = i;
						active--;
						break;
					}
					if(recChannel[i] < 0 && firstFree < 0) { firstFree = i; }
				}
				if(slot < 0) { slot = active > 128 ? 0 : firstFree; }
				if(slot >= 0)
				{
					recChannel[slot] = ch;
					recKey[slot] = event.data1;
					recType[slot] = channelType[ch];
					recWeight[slot] = 100 * event.data2 * event.data2 / 16129;
					recFlags[slot] = cc4Held[ch] ? 3 : 1;
					recTime[slot] = ms < 0x10000 ? ms : 0;
					active++;
					int metric = childTailMetric(ms, channelWeight, recChannel, recType, recWeight, recFlags, recTime);
					if(metric > maxMetric) { maxMetric = metric; }
				}
			}
			else if(high == 0x80 || (high == 0x90 && event.data2 == 0))
			{
				for(int i = 0; i < recChannel.length; i++)
				{
					if(recChannel[i] == ch && recKey[i] == event.data1)
					{
						if((recFlags[i] & 2) == 0) { recTime[i] = ms < 0x10000 ? ms : 0; }
						recFlags[i] = (recFlags[i] & 0xFA) | 4;
						break;
					}
				}
			}
			else if(high == 0xB0)
			{
				int cc = event.data1 & 0x7F;
				int value = event.data2 & 0x7F;
				if(cc == 7 || cc == 11)
				{
					if(cc == 7) { volume14[ch] = value << 7; }
					else { expression14[ch] = value << 7; }
					int scaled = (volume14[ch] * expression14[ch]) / 16256;
					int squared = ((scaled & 0xFFFF) * (scaled & 0xFFFF)) / 16256;
					channelWeight[ch] = (100 * squared) / 16256;
					int metric = childTailMetric(ms, channelWeight, recChannel, recType, recWeight, recFlags, recTime);
					if(metric > maxMetric) { maxMetric = metric; }
				}
				else if(cc == 4)
				{
					cc4Held[ch] = value >= 0x40;
					for(int i = 0; i < recChannel.length; i++)
					{
						if(recChannel[i] == ch)
						{
							if(cc4Held[ch]) { recFlags[i] |= 2; }
							else if((recFlags[i] & 6) == 6)
							{
								recTime[i] = ms < 0x10000 ? ms : 0;
								recFlags[i] &= 0xFD;
							}
						}
					}
				}
				else if(cc == 120 || cc == 123)
				{
					for(int i = 0; i < recChannel.length; i++)
					{
						if(recChannel[i] == ch)
						{
							if(cc == 120)
							{
								recChannel[i] = -1;
								recFlags[i] = 0;
								active--;
							}
							else
							{
								if((recFlags[i] & 2) == 0) { recTime[i] = ms < 0x10000 ? ms : 0; }
								recFlags[i] = (recFlags[i] & 0xFA) | 4;
							}
						}
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

	static int clampRate(int sampleRate)
	{
		if(sampleRate <= 0) { return DEFAULT_RATE; }
		if(sampleRate < 8000) { return 8000; }
		if(sampleRate > 48000) { return 48000; }
		return sampleRate;
	}

	static int clampVoices(int voices)
	{
		if(voices <= 0) { return DEFAULT_VOICES; }
		if(voices < 8) { return 8; }
		if(voices > 256) { return 256; }
		return voices;
	}

	private static int clampTempo(int value)
	{
		if(value < 29296) { return 29296; }
		return value > 15000000 ? 15000000 : value;
	}

	private static int childTailMetric(int ms, int[] channelWeight, int[] recChannel, int[] recType,
			int[] recWeight, int[] recFlags, int[] recTime)
	{
		int total = 0;
		for(int i = 0; i < recChannel.length; i++)
		{
			if((recFlags[i] & 4) != 0 && (recFlags[i] & 2) == 0 && ms - (recTime[i] & 0xFFFF) > 0x0F)
			{
				recChannel[i] = -1;
				recFlags[i] = 0;
			}
			if(recFlags[i] != 0 && recChannel[i] >= 0)
			{
				int weight = recWeight[i];
				int age = ms - (recTime[i] & 0xFFFF);
				if((recFlags[i] & 3) != 0)
				{
					if(recType[i] == 1)
					{
						for(int t = 200; t < age && weight > 0; t += 200) { weight /= 4; }
					}
					else if(recType[i] == 2)
					{
						for(int t = 100; t < age && weight > 0; t += 100) { weight /= 8; }
					}
				}
				else
				{
					for(int t = 10; t < age && weight > 0; t += 10) { weight /= 16; }
				}
				total += channelWeight[recChannel[i]] * weight / 100;
			}
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
		final int tempo;

		SequenceEvent(long tick, int track, int order, int command, int channel, int data1, int data2, int tempo)
		{
			this.tick = tick;
			this.track = track;
			this.order = order;
			this.command = command;
			this.channel = channel;
			this.data1 = data1;
			this.data2 = data2;
			this.tempo = tempo;
		}
	}
}

