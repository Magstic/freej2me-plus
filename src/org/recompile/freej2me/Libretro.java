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
package org.recompile.freej2me;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

import java.awt.image.DataBufferInt;
import java.util.Timer;
import java.util.TimerTask;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.microedition.midlet.MIDlet;

public class Libretro
{
	private int lcdWidth;
	private int lcdHeight;

	private boolean soundEnabled = true;

	private byte[] frameBuffer = new byte[800*800*3];
	private final byte[] frameHeader = new byte[]{(byte)0xFE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

	private int mousex;
	private int mousey;

	/* 
	 * StringBuilder used to get the updated configs from the libretro core
	 * String[] used to tokenize each setting as its own string.
	 */
	String[] cfgtokens;

	LibretroIO lio;

	public static void main(String args[])
	{
		Mobile.clearOldLog();
		Libretro app = new Libretro(args);
	}

	public Libretro(String args[])
	{
		lcdWidth  = Mobile.lcdWidth;
		lcdHeight = Mobile.lcdHeight;

		/* 
		 * Notify the MIDlet class that this version of FreeJ2ME is for Libretro, which disables 
		 * the ability to close the jar when a J2ME app requests an exit as this can cause segmentation
		 * faults on libretro frontends and also close the unexpectedly.
		*/
		Mobile.getPlatform().isLibretro = true;

		/* 
		 * Checks if the arguments were received from the commandline -> width, height, rotate, phonetype, fps, sound, ...
		 * 
		 * NOTE:
		 * Due to differences in how linux and win32 pass their cmd arguments, we can't explictly check for a given size
		 * on the argv array. Linux includes the "java", "-jar" and "path/to/freej2me" into the array while WIN32 doesn't.
		 */
		lcdWidth =  Integer.parseInt(args[0]);
		lcdHeight = Integer.parseInt(args[1]);

		if(Integer.parseInt(args[2]) == 1) { Mobile.rotateDisplay = true; }

		Mobile.lg = false;
		Mobile.motorola = false;
		Mobile.motoTriplets = false;
		Mobile.motoV8 = false;
		Mobile.nokiaKeyboard = false;
		Mobile.sagem = false;
		Mobile.siemens = false;
		Mobile.siemensold = false;

		if(Integer.parseInt(args[3]) == 1)      { Mobile.lg = true;    }
		else if(Integer.parseInt(args[3]) == 2) { Mobile.motorola = true;  }
		else if(Integer.parseInt(args[3]) == 3) { Mobile.motoTriplets = true; }
		else if(Integer.parseInt(args[3]) == 4) { Mobile.motoV8 = true; }
		else if(Integer.parseInt(args[3]) == 5) { Mobile.nokiaKeyboard = true; }
		else if(Integer.parseInt(args[3]) == 6) { Mobile.sagem = true; }
		else if(Integer.parseInt(args[3]) == 7) { Mobile.siemens = true; }
		else if(Integer.parseInt(args[3]) == 8) { Mobile.siemensold = true; }

		Mobile.limitFPS = Integer.parseInt(args[4]);

		if(Integer.parseInt(args[5]) == 0) { soundEnabled = false; }

		if(Integer.parseInt(args[6]) == 1) { Mobile.useCustomMidi = true; }

		/* Dump Audio Streams will not be a per-game FreeJ2ME config, so it will have to be set every time for now */
		if(Integer.parseInt(args[7]) == 1) { Mobile.dumpAudioStreams = true; }

		/* Same for Logging Level */
		if(Integer.parseInt(args[8]) == 0) { Mobile.logging = false; }
		else { Mobile.logging = true; Mobile.minLogLevel = (byte) (Integer.parseInt(args[8])-1); }

		/* No Alpha on Blank Images SpeedHack is a per-game config */
		if(Integer.parseInt(args[9]) == 0) { Mobile.noAlphaOnBlankImages = false; }
		else { Mobile.noAlphaOnBlankImages = true; }

		/* LCD Backlight Mask color index. */
		Mobile.maskIndex = Integer.parseInt(args[10]);

		/* The Non-Fatal Null Images compat setting is also a per-game config */
		if(Integer.parseInt(args[11]) == 0) { Mobile.compatNonFatalNullImages = false; }
		else { Mobile.compatNonFatalNullImages = true; }

		/* Once it finishes parsing all arguments, it's time to set up freej2me-lr */

		Mobile.setPlatform(new MobilePlatform(lcdWidth, lcdHeight));

		Mobile.config = new Config();
		Mobile.config.onChange = new Runnable() { public void run() { settingsChanged(); } };

		lio = new LibretroIO();

		lio.start();
		
		System.out.println("+READY");
		System.out.flush();
	}

	private class LibretroIO
	{
		private Timer keytimer;
		private TimerTask keytask;

		public void start()
		{
			keytimer = new Timer();
			keytask = new LibretroTimerTask();
			keytimer.schedule(keytask, 0, 1);
		}

		private class LibretroTimerTask extends TimerTask
		{
			private int bin;
			private int[] din = new int[5];
			private int count = 0;
			private int code;
			private byte[] buffer;
			private int bytesRead = 0;
			private String path;

			public void run()
			{
				try // to read keys
				{
					while(true)
					{
						bin = System.in.read();
						if(bin==-1) { return; }
						//System.out.print(" "+bin);
						din[count] = (int)(bin & 0xFF);
						count++;

						/* Check inputs */
						if (count==5)
						{
							count = 0;
							code = (din[1]<<24) | (din[2]<<16) | (din[3]<<8) | din[4];
							switch(din[0])
							{
								//case 0: // keyboard key up (unused)
								//break;

								//case 1:	// keyboard key down (unused)
								//break;

								case 2:	// joypad key up
									MobilePlatform.pressedKeys[code] = false;
								break;

								case 3: // joypad key down					
									MobilePlatform.pressedKeys[code] = true;
								break;

								case 4: // mouse up
									mousex = (din[1]<<8) | din[2];
									mousey = (din[3]<<8) | din[4];
									
									MobilePlatform.pointerReleased[0] = 1;
									if(!Mobile.rotateDisplay)
									{
										MobilePlatform.pointerReleased[1] = mousex;
										MobilePlatform.pointerReleased[2] = mousey;
									}
									else
									{
										MobilePlatform.pointerReleased[1] = lcdWidth-mousey;
										MobilePlatform.pointerReleased[2] = mousex;
									}
								break;

								case 5: // mouse down
									mousex = (din[1]<<8) | din[2];
									mousey = (din[3]<<8) | din[4];

									MobilePlatform.pointerPressed[0] = 1;
									if(!Mobile.rotateDisplay)
									{
										MobilePlatform.pointerPressed[1] = mousex;
										MobilePlatform.pointerPressed[2] = mousey;
									}
									else
									{
										MobilePlatform.pointerPressed[1] = lcdWidth-mousey;
										MobilePlatform.pointerPressed[2] = mousex;
									}
								break;

								case 6: // mouse drag
									mousex = (din[1]<<8) | din[2];
									mousey = (din[3]<<8) | din[4];

									MobilePlatform.pointerDragged[0] = 1;
									if(!Mobile.rotateDisplay)
									{
										MobilePlatform.pointerDragged[1] = mousex;
										MobilePlatform.pointerDragged[2] = mousey;
									}
									else
									{
										MobilePlatform.pointerDragged[1] = lcdWidth-mousey;
										MobilePlatform.pointerDragged[2] = mousex;
									}
								break;

								case 10: // load jar
									buffer = new byte[code];
									bytesRead = System.in.read(buffer);

									path = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

									if(Mobile.getPlatform().load(getFormattedLocation(path.toString())))
									{
										// Check config
										Mobile.config.init();

										/* Override configs with the ones passed through commandline */
										Mobile.config.settings.put("width",  ""+lcdWidth);
										Mobile.config.settings.put("height", ""+lcdHeight);

										if(Mobile.rotateDisplay)   { Mobile.config.settings.put("rotate", "on");  }
										if(!Mobile.rotateDisplay)  { Mobile.config.settings.put("rotate", "off"); }

										if(Mobile.lg)                 { Mobile.config.settings.put("phone", "LG");    }
										else if(Mobile.motorola)      { Mobile.config.settings.put("phone", "Motorola");  }
										else if(Mobile.motoTriplets)  { Mobile.config.settings.put("phone", "MotoTriplets"); }
										else if(Mobile.motoV8)        { Mobile.config.settings.put("phone", "MotoV8"); }
										else if(Mobile.nokiaKeyboard) { Mobile.config.settings.put("phone", "NokiaKeyboard"); }
										else if(Mobile.sagem)         { Mobile.config.settings.put("phone", "Sagem"); }
										else if(Mobile.siemens)       { Mobile.config.settings.put("phone", "Siemens"); }
										else if(Mobile.siemensold)    { Mobile.config.settings.put("phone", "SiemensOld"); }
										else                          { Mobile.config.settings.put("phone", "Standard"); }

										if(soundEnabled)   { Mobile.config.settings.put("sound", "on");  }
										if(!soundEnabled)  { Mobile.config.settings.put("sound", "off"); }

										Mobile.config.settings.put("fps", "" + Mobile.limitFPS);

										if(!Mobile.useCustomMidi)  { Mobile.config.settings.put("soundfont", "Default"); }
										else                       { Mobile.config.settings.put("soundfont", "Custom");  }

										if(!Mobile.noAlphaOnBlankImages) { Mobile.config.settings.put("spdhacknoalpha", "off"); }
										else                             { Mobile.config.settings.put("spdhacknoalpha", "on"); }

										if(Mobile.maskIndex == 0)      { Mobile.config.settings.put("backlightcolor", "Disabled"); }
										else if(Mobile.maskIndex == 1) { Mobile.config.settings.put("backlightcolor", "Green"); }
										else if(Mobile.maskIndex == 2) { Mobile.config.settings.put("backlightcolor", "Cyan"); }
										else if(Mobile.maskIndex == 3) { Mobile.config.settings.put("backlightcolor", "Orange"); }
										else if(Mobile.maskIndex == 4) { Mobile.config.settings.put("backlightcolor", "Violet"); }
										else if(Mobile.maskIndex == 5) { Mobile.config.settings.put("backlightcolor", "Red"); }

										if(!Mobile.compatNonFatalNullImages) { Mobile.config.settings.put("compatnonfatalnullimage", "off"); }
										else                                 { Mobile.config.settings.put("compatnonfatalnullimage", "on"); }

										Mobile.config.saveConfig();
										settingsChanged();

										// Run jar
										Mobile.getPlatform().runJar();
									}
									else
									{
										Mobile.log(Mobile.LOG_ERROR, Libretro.class.getPackage().getName() + "." + Libretro.class.getSimpleName() + ": " + "Couldn't load jar...");
										System.exit(0);
									}
								break;

								case 11: // set save path //
									buffer = new byte[code];
									bytesRead = System.in.read(buffer);

									Mobile.getPlatform().dataPath = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
								break;

								case 13:
									/* Received updated settings from libretro core */
									buffer = new byte[code];
									bytesRead = System.in.read(buffer);
									
									String cfgvars = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
									/* Tokens: [0]="FJ2ME_LR_OPTS:", [1]=width, [2]=height, [3]=rotate, [4]=phone, [5]=fps, ... */
									cfgtokens = cfgvars.split("[| x]", 0);
									/* 
									 * cfgtokens[0] is the string used to indicate that the 
									 * received string is a config update. Only useful for debugging, 
									 * but better leave it in there as we might make adjustments later.
									 */
									Mobile.config.settings.put("width",  ""+Integer.parseInt(cfgtokens[1]));
									Mobile.config.settings.put("height", ""+Integer.parseInt(cfgtokens[2]));

									if(Integer.parseInt(cfgtokens[3])==1) { Mobile.config.settings.put("rotate", "on");  }
									if(Integer.parseInt(cfgtokens[3])==0) { Mobile.config.settings.put("rotate", "off"); }

									if(Integer.parseInt(cfgtokens[4])==0) { Mobile.config.settings.put("phone", "Standard"); }
									if(Integer.parseInt(cfgtokens[4])==1) { Mobile.config.settings.put("phone", "LG");    }
									if(Integer.parseInt(cfgtokens[4])==2) { Mobile.config.settings.put("phone", "Motorola");  }
									if(Integer.parseInt(cfgtokens[4])==3) { Mobile.config.settings.put("phone", "MotoTriplets"); }
									if(Integer.parseInt(cfgtokens[4])==4) { Mobile.config.settings.put("phone", "MotoV8"); }
									if(Integer.parseInt(cfgtokens[4])==5) { Mobile.config.settings.put("phone", "NokiaKeyboard"); }
									if(Integer.parseInt(cfgtokens[4])==6) { Mobile.config.settings.put("phone", "Sagem"); }
									if(Integer.parseInt(cfgtokens[4])==7) { Mobile.config.settings.put("phone", "Siemens"); }
									if(Integer.parseInt(cfgtokens[4])==8) { Mobile.config.settings.put("phone", "SiemensOld"); }

									Mobile.config.settings.put("fps", ""+cfgtokens[5]);

									if(Integer.parseInt(cfgtokens[6])==1) { Mobile.config.settings.put("sound", "on");  }
									if(Integer.parseInt(cfgtokens[6])==0) { Mobile.config.settings.put("sound", "off"); }

									if(Integer.parseInt(cfgtokens[7])==0) { Mobile.config.settings.put("soundfont", "Default"); }
									if(Integer.parseInt(cfgtokens[7])==1) { Mobile.config.settings.put("soundfont", "Custom");  }

									if(Integer.parseInt(cfgtokens[8])==1) { Mobile.dumpAudioStreams = true;  }
									if(Integer.parseInt(cfgtokens[8])==0) { Mobile.dumpAudioStreams = false; }

									if(Integer.parseInt(cfgtokens[9])==0) { Mobile.logging = false;  }
									else { Mobile.logging = true; Mobile.minLogLevel = (byte) (Integer.parseInt(cfgtokens[9])-1); }

									if(Integer.parseInt(cfgtokens[10])==0) { Mobile.noAlphaOnBlankImages = false;  }
									else { Mobile.noAlphaOnBlankImages = true; }

									if(Integer.parseInt(cfgtokens[11])==0) { Mobile.config.settings.put("backlightcolor", "Disabled"); }
									if(Integer.parseInt(cfgtokens[11])==1) { Mobile.config.settings.put("backlightcolor", "Green");    }
									if(Integer.parseInt(cfgtokens[11])==2) { Mobile.config.settings.put("backlightcolor", "Cyan");  }
									if(Integer.parseInt(cfgtokens[11])==3) { Mobile.config.settings.put("backlightcolor", "Orange"); }
									if(Integer.parseInt(cfgtokens[11])==4) { Mobile.config.settings.put("backlightcolor", "Violet"); }
									if(Integer.parseInt(cfgtokens[11])==5) { Mobile.config.settings.put("backlightcolor", "Red"); }

									if(Integer.parseInt(cfgtokens[12])==0) { Mobile.compatNonFatalNullImages = false;  }
									else { Mobile.compatNonFatalNullImages = true; }

									Mobile.config.saveConfig();
									settingsChanged();
								break;
								
								case 15:
									/* Send Frame to Libretro */
									try
									{				
										//frameHeader[0] = (byte)0xFE;
										frameHeader[1] = (byte)((lcdWidth>>8)&0xFF);
										frameHeader[2] = (byte)((lcdWidth)&0xFF);
										frameHeader[3] = (byte)((lcdHeight>>8)&0xFF);
										frameHeader[4] = (byte)((lcdHeight)&0xFF);
										//frameHeader[5] = (byte)rotateDysplay; ( seen in settingsChanged() )
										frameHeader[6] = (byte)((Mobile.vibrationDuration>>24) & 0xFF);
										frameHeader[7] = (byte)((Mobile.vibrationDuration>>16) & 0xFF);
										frameHeader[8] = (byte)((Mobile.vibrationDuration>>8) & 0xFF);
										frameHeader[9] = (byte)((Mobile.vibrationDuration) & 0xFF);

										frameHeader[10] = (byte)((Mobile.vibrationStrength>>24) & 0xFF);
										frameHeader[11] = (byte)((Mobile.vibrationStrength>>16) & 0xFF);
										frameHeader[12] = (byte)((Mobile.vibrationStrength>>8) & 0xFF);
										frameHeader[13] = (byte)((Mobile.vibrationStrength) & 0xFF);
										System.out.write(frameHeader, 0, 14);

										/* Vibration duration should be set to zero to prevent constant sends of the same data, so update it here */
										Mobile.vibrationDuration = 0;

										final int[] data = ((DataBufferInt) Mobile.getPlatform().getLCD().getRaster().getDataBuffer()).getData();

										for(int i=0; i<data.length; i++)
										{
											frameBuffer[3*i]   = (byte)((data[i]>>16)&0xFF);
											frameBuffer[3*i+1] = (byte)((data[i]>>8)&0xFF);
											frameBuffer[3*i+2] = (byte)((data[i])&0xFF);
										}

										System.out.write(frameBuffer, 0, data.length*3);
										System.out.flush();
									}
									catch (Exception e)
									{
										Mobile.log(Mobile.LOG_DEBUG, Libretro.class.getPackage().getName() + "." + Libretro.class.getSimpleName() + ": " + "Error sending frame: "+e.getMessage());
										System.exit(0);
									}
								break;
							}
							//System.out.flush();
						}
					}
				}
				catch (Exception e) { System.exit(0); }
			}
		} // timer
	} // LibretroIO

	private static String getFormattedLocation(String loc)
	{
		if (loc.startsWith("file://") || loc.startsWith("http://") || loc.startsWith("https://"))
			return loc;

		File file = new File(loc);
		if(!file.isFile())
		{
			Mobile.log(Mobile.LOG_ERROR, Libretro.class.getPackage().getName() + "." + Libretro.class.getSimpleName() + ": " + "File '" + loc + "' not found...");
			System.exit(0);
		}

		return file.toURI().toString();
	}

	private void settingsChanged()
	{
		Mobile.updateSettings();

		if(Mobile.rotateDisplay == true) { frameHeader[5] = (byte)1; } 
		else                             { frameHeader[5] = (byte)0; }
		
		if(lcdWidth != Mobile.lcdWidth || lcdHeight != Mobile.lcdHeight)
		{
			lcdWidth = Mobile.lcdWidth;
			lcdHeight = Mobile.lcdHeight;
			Mobile.getPlatform().resizeLCD(Mobile.lcdWidth, Mobile.lcdHeight);
		}
	}

}
