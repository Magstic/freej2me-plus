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

/*
	FreeJ2ME - AWT
*/

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

import javax.imageio.ImageIO;

public class FreeJ2ME
{

	private static FreeJ2ME app;
	private Frame main;
	private int lcdWidth;
	private int lcdHeight;
	private int scaleFactor = 1;

	private LCD lcd;

	private int xborder;
	private int yborder;
	
	// AWT GUI
	private AWTGUI awtGUI;

	public static void main(String args[])
	{
		Mobile.clearOldLog();
		FreeJ2ME app = new FreeJ2ME(args);
	}

	public FreeJ2ME(String args[])
	{
		main = new Frame("FreeJ2ME-Plus");
		main.setSize(350,450);
		/* Set a minimum allowed width and height so the menu bar is visible at all times */
		main.setMinimumSize(new Dimension(240, 240));
		main.setBackground(new Color(0,0,64));
		try
		{
			main.setIconImage(ImageIO.read(main.getClass().getResourceAsStream("/org/recompile/icon.png")));	
		}
		catch (Exception e) { }

		main.addWindowListener(new WindowAdapter()
		{
			public void windowClosing(WindowEvent e)
			{
				System.exit(0);
			}
		});

		// Setup Device //

		lcdWidth = Mobile.lcdWidth;
		lcdHeight = Mobile.lcdHeight;

		Mobile.setPlatform(new MobilePlatform(lcdWidth, lcdHeight));

		lcd = new LCD();
		lcd.setFocusable(true);

		Mobile.config = new Config();
		Mobile.config.onChange = new Runnable() { public void run() { settingsChanged(); } };

		/* Add LCD screen to FreeJ2ME's AWT frame */
		main.add(lcd);

		awtGUI = new AWTGUI(Mobile.config);
		awtGUI.setMainFrame(main);
		/* Append the awt menu bar into FreeJ2ME's frame */
		main.setMenuBar(awtGUI.getMenuBar());

		if(args.length>=1)
		{
			awtGUI.loadJarFile(getFormattedLocation(args[0]), true);
		}
		if(args.length>=3)
		{
			lcdWidth = Integer.parseInt(args[1]);
			lcdHeight = Integer.parseInt(args[2]);
		}
		if(args.length>=4)
		{
			scaleFactor = Integer.parseInt(args[3]);
		}

		/* Inputs should only be registered if a jar has been loaded, otherwise AWT will throw NullPointerException */
		lcd.addKeyListener(new KeyListener()
		{
			public void keyPressed(KeyEvent e)
			{
				if(awtGUI.hasLoadedFile())
				{
					int keycode = e.getKeyCode();
					int mobikey = getMobileKey(keycode);
					
					switch(keycode) // Handle emulator control keys
					{
						case KeyEvent.VK_PLUS:
						case KeyEvent.VK_ADD:
							scaleFactor++;
							main.setSize(lcdWidth * scaleFactor + xborder, lcdHeight * scaleFactor + yborder);
						break;
						case KeyEvent.VK_MINUS:
						case KeyEvent.VK_SUBTRACT:
							if( scaleFactor > 1 )
							{
								scaleFactor--;
								main.setSize(lcdWidth * scaleFactor + xborder, lcdHeight * scaleFactor + yborder);
							}
						break;
						case KeyEvent.VK_C:
							if(e.isControlDown())
							{
								ScreenShot.takeScreenshot(false);
							}
						break;
					}
					
					if (mobikey == Integer.MIN_VALUE) // Ignore events from keys not mapped to a phone keypad key (AWTGUI does use 0, so this can't mirror libretro)
					{
						return; 
					}
					
					MobilePlatform.pressedKeys[mobikey] = true;
				}
			}

			public void keyReleased(KeyEvent e)
			{
				if(awtGUI.hasLoadedFile()) 
				{
					int mobikey = getMobileKey(e.getKeyCode());
					
					if (mobikey == Integer.MIN_VALUE) // Ignore events from keys not mapped to a phone keypad key (AWTGUI does use 0, so this can't mirror libretro)
					{
						return; 
					}
					
					MobilePlatform.pressedKeys[mobikey] = false;
				}
			}

			public void keyTyped(KeyEvent e) { }

		});

		lcd.addMouseListener(new MouseListener()
		{

			public void mousePressed(MouseEvent e)
			{
				if(awtGUI.hasLoadedFile()) 
				{
					int x = (int)((e.getX()-lcd.cx) * lcd.scalex);
					int y = (int)((e.getY()-lcd.cy) * lcd.scaley);

					// Adjust the pointer coords if the screen is rotated, same for mouseReleased
					if(Mobile.rotateDisplay)
					{
						x = (int)((lcd.ch-(e.getY()-lcd.cy)) * lcd.scaley);
						y = (int)((e.getX()-lcd.cx) * lcd.scalex);
					}

					MobilePlatform.pointerPressed[0] = 1;
					MobilePlatform.pointerPressed[1] = x;
					MobilePlatform.pointerPressed[2] = y;
				}
			}

			public void mouseReleased(MouseEvent e)
			{
				if(awtGUI.hasLoadedFile()) 
				{
					int x = (int)((e.getX()-lcd.cx) * lcd.scalex);
					int y = (int)((e.getY()-lcd.cy) * lcd.scaley);

					if(Mobile.rotateDisplay)
					{
						x = (int)((lcd.ch-(e.getY()-lcd.cy)) * lcd.scaley);
						y = (int)((e.getX()-lcd.cx) * lcd.scalex);
					}

					MobilePlatform.pointerReleased[0] = 1;
					MobilePlatform.pointerReleased[1] = x;
					MobilePlatform.pointerReleased[2] = y;
				}
			}

			public void mouseExited(MouseEvent e) { }
			public void mouseEntered(MouseEvent e) { }
			public void mouseClicked(MouseEvent e) { }

		});

		lcd.addMouseMotionListener(new MouseMotionAdapter() 
		{
			public void mouseDragged(MouseEvent e)
			{
				if(awtGUI.hasLoadedFile()) 
				{
					int x = (int)((e.getX()-lcd.cx) * lcd.scalex);
					int y = (int)((e.getY()-lcd.cy) * lcd.scaley);

					if(Mobile.rotateDisplay)
					{
						x = (int)((lcd.ch-(e.getY()-lcd.cy)) * lcd.scaley);
						y = (int)((e.getX()-lcd.cx) * lcd.scalex);
					}
					
					MobilePlatform.pointerDragged[0] = 1;
					MobilePlatform.pointerDragged[1] = x;
					MobilePlatform.pointerDragged[2] = y;
				}
			}
		});

		main.addComponentListener(new ComponentAdapter()
		{
			public void componentResized(ComponentEvent e)
			{
				resize();
			}
		});

		main.setVisible(true);
		main.pack();

		resize();
		main.setSize(lcdWidth*scaleFactor+xborder, lcdHeight*scaleFactor+yborder);

		// Set painter right before the jar is loaded
		Mobile.getPlatform().setPainter(new Runnable()
		{
			public void run()
			{
				/* Set menuBar option states based on loaded config */
				if(awtGUI.hasJustLoaded()) { awtGUI.updateOptions(); }

				/* Only update mem dialog's stats if it is visible */
				if(awtGUI.awtDialogs[2].isVisible()) { awtGUI.updateMemStatDialog(); }

				/* Whenever AWT GUI notifies that its menu options were changed, update settings */
				if(awtGUI.hasChanged()) { settingsChanged(); awtGUI.clearChanged(); }

				lcd.repaint();
			}
		});

		if(args.length<1)
		{
			while(!awtGUI.hasLoadedFile())
			{
				try{ Thread.sleep(1000); }
				catch (InterruptedException e) { }
			}
		}
		if(Mobile.getPlatform().load(awtGUI.getJarPath()))
		{
			Mobile.config.init();

			/* Allows FreeJ2ME to set the width and height passed as cmd arguments. */
			if(args.length>=3)
			{
				lcdWidth = Integer.parseInt(args[1]);
				lcdHeight = Integer.parseInt(args[2]);
				Mobile.config.settings.put("width",  ""+lcdWidth);
				Mobile.config.settings.put("height", ""+lcdHeight);
			}

			settingsChanged();

			Mobile.getPlatform().runJar();

		}
		else
		{
			Mobile.log(Mobile.LOG_ERROR, FreeJ2ME.class.getPackage().getName() + "." + FreeJ2ME.class.getSimpleName() + ": " + "Couldn't load jar...");
		}

	}

	private static String getFormattedLocation(String loc)
	{
		if (loc.startsWith("file://") || loc.startsWith("http://") || loc.startsWith("https://"))
			return loc;

		File file = new File(loc);
		if(! file.isFile())
		{
			Mobile.log(Mobile.LOG_ERROR, FreeJ2ME.class.getPackage().getName() + "." + FreeJ2ME.class.getSimpleName() + ": " + "File not found...");
			System.exit(0);
		}

		return file.toURI().toString();
	}

	private void settingsChanged()
	{
		boolean hasRotated = Mobile.updateSettings();

		// Create a standard size LCD if not rotated, else invert window's width and height.
		if(Mobile.lcdWidth != lcdWidth || Mobile.lcdHeight != lcdHeight || hasRotated) 
		{
			Mobile.getPlatform().resizeLCD(Mobile.lcdWidth, Mobile.lcdHeight);

			if(!Mobile.rotateDisplay) 
			{
				lcdWidth = Mobile.lcdWidth;
				lcdHeight = Mobile.lcdHeight;
			}
			else 
			{
				lcdWidth = Mobile.lcdHeight;
				lcdHeight = Mobile.lcdWidth;
			}
			resize();
			main.setSize(lcdWidth*scaleFactor+xborder , lcdHeight*scaleFactor+yborder);
		}
		
	}

	private int getMobileKey(int keycode)
	{
		for(int i = 0; i < awtGUI.inputKeycodes.length; i++) 
		{
			if(keycode == awtGUI.inputKeycodes[i]) { return Mobile.convertAWTKeycode(i);}
		}
		return Integer.MIN_VALUE;
	}

	private void resize()
	{
		xborder = main.getInsets().left+main.getInsets().right;
		yborder = main.getInsets().top+main.getInsets().bottom;

		double vw = (main.getWidth()-xborder)*1;
		double vh = (main.getHeight()-yborder)*1;

		double nw = lcdWidth;
		double nh = lcdHeight;

		nw = vw;
		nh = nw*((double)lcdHeight/(double)lcdWidth);

		if(nh>vh)
		{
			nh = vh;
			nw = nh*((double)lcdWidth/(double)lcdHeight);
		}

		lcd.updateScale((int)nw, (int)nh);
	}

	private class LCD extends Canvas
	{
		public int cx=0;
		public int cy=0;
		public int cw=240;
		public int ch=320;

		public double scalex=1;
		public double scaley=1;

		public void updateScale(int vw, int vh)
		{
			cx = (this.getWidth()-vw)/2;
			cy = (this.getHeight()-vh)/2;
			cw = vw;
			ch = vh;
			scalex = (double)lcdWidth/(double)vw;
			scaley = (double)lcdHeight/(double)vh;
		}

		@Override
        public void update(Graphics g) {
            // Use paint method directly to avoid flicker
            paint(g);
        }

		public void paint(Graphics g)
		{
			if(!Mobile.rotateDisplay) { g.drawImage(Mobile.getPlatform().getLCD(), cx, cy, cw, ch, null); }
			else
			{
				final Graphics2D cgc = (Graphics2D)this.getGraphics();
				// Rotate the FB 90 degrees counterclockwise with an adjusted pivot
				cgc.rotate(Math.toRadians(-90), ch/2, ch/2);
				// Draw the rotated FB with adjusted cy and cx values
				cgc.drawImage(Mobile.getPlatform().getLCD(), 0, cx, ch, cw, null);
			}
		}
	}
}
