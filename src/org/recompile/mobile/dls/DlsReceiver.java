/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
*/
package org.recompile.mobile.dls;

import java.util.Arrays;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/** Realtime Java Sound receiver that renders incoming MIDI through a loaded DLS bank. */
public final class DlsReceiver extends SynthesisSupport implements Receiver, Runnable
{
	private final Object lock = new Object();
	private final PreviewRenderer renderer;
	private final SourceDataLine line;
	private final int blockFrames;
	private final int[] mixBlock;
	private final int[] reverbBlock;
	private final int[] chorusBlock;
	private final byte[] pcmBytes;
	private final ChorusEffect chorus;
	private final ReverbEffect reverb;
	private final EffectGate chorusGate;
	private final EffectGate reverbGate;
	private volatile boolean open = true;

	DlsReceiver(DlsBank bank, int sampleRate, int voices, boolean reverbEnabled, boolean chorusEnabled, int childTailInput) throws MidiUnavailableException
	{
		renderer = new PreviewRenderer(bank, sampleRate, reverbEnabled, chorusEnabled, voices, true);
		renderer.childTailGainQ16 = childTailGainQ16(childTailInput < 1 ? 1 : childTailInput);
		blockFrames = defaultRenderBlockFrames(sampleRate);
		mixBlock = new int[blockFrames * 2];
		reverbBlock = new int[blockFrames];
		chorusBlock = new int[blockFrames];
		pcmBytes = new byte[blockFrames * 4];
		chorus = new ChorusEffect(sampleRate);
		reverb = new ReverbEffect(sampleRate);
		chorusGate = new EffectGate(chorus.tailFrames(), blockFrames);
		reverbGate = new EffectGate(reverb.tailFrames(), blockFrames);
		renderer.reverbBus = reverbBlock;
		renderer.chorusBus = chorusBlock;

		try
		{
			AudioFormat format = new AudioFormat((float) sampleRate, 16, 2, true, false);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format, blockFrames * 4 * 4);
			line.start();
		}
		catch(Exception e) { throw new MidiUnavailableException(e.getMessage()); }

		Thread thread = new Thread(this, "FreeJ2ME-DLS-Audio");
		thread.setDaemon(true);
		thread.start();
	}

	public void send(MidiMessage message, long timeStamp)
	{
		if(!open || message == null) { return; }
		synchronized(lock)
		{
			if(message instanceof ShortMessage)
			{
				ShortMessage shortMessage = (ShortMessage) message;
				int status = shortMessage.getCommand();
				if(status >= 0x80 && status <= 0xE0)
				{
					renderer.handle(new MidiEvent(0, 0, 0, status, shortMessage.getChannel(),
							shortMessage.getData1(), shortMessage.getData2(), -1, null));
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
				renderer.handle(new MidiEvent(0, 0, 0, 0xF0, -1, 0, 0, -1, data));
			}
		}
	}

	public void close()
	{
		open = false;
		synchronized(lock) { renderer.voices.clear(); }
		try { line.stop(); } catch(Throwable ignored) { }
		try { line.flush(); } catch(Throwable ignored) { }
		try { line.close(); } catch(Throwable ignored) { }
	}

	public void run()
	{
		while(open)
		{
			int frames = renderBlock();
			if(frames > 0) { line.write(pcmBytes, 0, frames * 4); }
		}
	}

	private int renderBlock()
	{
		synchronized(lock)
		{
			Arrays.fill(mixBlock, 0);
			Arrays.fill(reverbBlock, 0);
			Arrays.fill(chorusBlock, 0);
			renderer.currentBlockReverbActive = false;
			renderer.currentBlockChorusActive = false;
			renderer.mixUntil(mixBlock, 0, blockFrames);
			renderer.childDynamics.process(mixBlock, 0, mixBlock, 0, 2, blockFrames, false);
			if(renderer.chorusEnabled && chorusGate.processThisBlock(renderer.currentBlockChorusActive)) { chorus.process(chorusBlock, mixBlock, 0, blockFrames); }
			if(renderer.reverbEnabled && reverbGate.processThisBlock(renderer.currentBlockReverbActive)) { reverb.process(reverbBlock, mixBlock, 0, blockFrames); }
			renderer.mixDynamics.process(mixBlock, 0, mixBlock, 0, 2, blockFrames, false);
			for(int i = 0; i < blockFrames * 2; i++)
			{
				short sample = finalMixSample(mixBlock[i]);
				pcmBytes[i * 2] = (byte) (sample & 0xFF);
				pcmBytes[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
			}
			return blockFrames;
		}
	}
}

