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

import static org.recompile.mobile.dls.SynthesisSupport.*;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import org.recompile.mobile.Mobile;

/** Realtime Java Sound receiver that renders incoming MIDI through a loaded DLS bank. */
public final class DlsReceiver implements Receiver, Runnable
{
	private static final int SAMPLE_RATE = 44100;

	private final Object lock = new Object();
	private final DlsRenderer renderer;
	private final SourceDataLine line;
	private final int blockFrames;
	private final int[] mixBlock;
	private final byte[] pcmBytes;
	private volatile boolean open = true;

	DlsReceiver(DlsBank bank, int childTailInput) throws MidiUnavailableException
	{
		renderer = new DlsRenderer(bank, SAMPLE_RATE);
		renderer.childTailGainQ16 = childTailGainQ16(childTailInput < 1 ? 1 : childTailInput);
		blockFrames = defaultRenderBlockFrames(SAMPLE_RATE);
		mixBlock = new int[blockFrames * 2];
		pcmBytes = new byte[blockFrames * 4];

		SourceDataLine audioLine = null;
		try
		{
			AudioFormat format = new AudioFormat((float) SAMPLE_RATE, 16, 2, true, false);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			audioLine = (SourceDataLine) AudioSystem.getLine(info);
			audioLine.open(format, blockFrames * 4 * 4);
			audioLine.start();
		}
		catch(Exception e)
		{
			if(audioLine != null) { audioLine.close(); }
			MidiUnavailableException error = new MidiUnavailableException(e.getMessage());
			error.initCause(e);
			throw error;
		}
		line = audioLine;

		Thread thread = new Thread(this, "FreeJ2ME-DLS-Audio");
		thread.setDaemon(true);
		thread.start();
	}

	public void send(MidiMessage message, long timeStamp)
	{
		if(message == null) { return; }
		synchronized(lock)
		{
			if(!open) { return; }
			if(message instanceof ShortMessage)
			{
				ShortMessage shortMessage = (ShortMessage) message;
				int status = shortMessage.getCommand();
				if(status >= 0x80 && status <= 0xE0)
				{
					renderer.handle(status, shortMessage.getChannel(), shortMessage.getData1(), shortMessage.getData2());
				}
			}
			else if(message instanceof SysexMessage)
			{
				byte[] data = ((SysexMessage) message).getData();
				if(data.length > 0 && (data[data.length - 1] & 0xFF) == 0xF7)
				{
					byte[] trimmed = new byte[data.length - 1];
					System.arraycopy(data, 0, trimmed, 0, trimmed.length);
					data = trimmed;
				}
				renderer.sysex(data);
			}
		}
	}

	public void close()
	{
		synchronized(lock)
		{
			if(!open) { return; }
			open = false;
			renderer.voices.clear();
		}
		line.stop();
		line.flush();
		line.close();
	}

	public void run()
	{
		try
		{
			while(open)
			{
				renderBlock();
				if(open) { line.write(pcmBytes, 0, pcmBytes.length); }
			}
		}
		catch(Exception e)
		{
			if(open) { Mobile.log(Mobile.LOG_ERROR, DlsReceiver.class.getPackage().getName() + "." + DlsReceiver.class.getSimpleName() + ": " + "DLS playback failed: " + e.getMessage()); }
		}
		finally { close(); }
	}

	private void renderBlock()
	{
		synchronized(lock)
		{
			renderer.render(mixBlock, blockFrames);
			renderer.childDynamics.process(mixBlock, blockFrames);
			renderer.mixDynamics.process(mixBlock, blockFrames);
			for(int i = 0; i < blockFrames * 2; i++)
			{
				short sample = finalMixSample(mixBlock[i]);
				pcmBytes[i * 2] = (byte)(sample & 0xFF);
				pcmBytes[i * 2 + 1] = (byte)((sample >>> 8) & 0xFF);
			}
		}
	}
}
