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
import org.recompile.mobile.PlatformImage;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.lang.Exception;
import java.util.Date;
import java.text.SimpleDateFormat;

public class ScreenShot
{
	public static void takeScreenshot(boolean saveToHomeDir)
	{
		try
		{
			Date date = new Date();
			String fileName;
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss");

			if (saveToHomeDir == true)
			{
				fileName = System.getProperty("user.home") + "/Pictures/Screenshot from " +
							dateFormat.format(date) + ".png";
			}
			else
			{
				String screenshotPath = Mobile.getPlatform().dataPath + "screenshots";
				try
				{
					File scrShotDir = new File(screenshotPath);
					if (!scrShotDir.exists()) { scrShotDir.mkdirs(); }
				}
				catch (Exception e)
				{
					Mobile.log(Mobile.LOG_ERROR, ScreenShot.class.getPackage().getName() + "." + ScreenShot.class.getSimpleName() + ": " + "Problem Creating Screenshot Path "+ screenshotPath);
					Mobile.log(Mobile.LOG_ERROR, ScreenShot.class.getPackage().getName() + "." + ScreenShot.class.getSimpleName() + ": " + e.getMessage());
				}
				fileName = screenshotPath + "/Screenshot from " +
							dateFormat.format(date) + ".png";
			}

			File outputfile = new File(fileName);
			Dimension size = FreeJ2ME.app.getDisplaySize();
			BufferedImage image = new BufferedImage(Math.max(1, size.width), Math.max(1, size.height), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			PlatformImage frontbuffer = Mobile.getPlatform().getLcdFrontbuffer();
			synchronized (frontbuffer)
			{
				BufferedImage frame = frontbuffer.getPresentationCanvas();
				if(frame.getWidth() != frontbuffer.getWidth())
				{
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				}
				if(Mobile.rotateDisplay == 90) { g.translate(image.getWidth(), 0); }
				else if(Mobile.rotateDisplay == 180) { g.translate(image.getWidth(), image.getHeight()); }
				else if(Mobile.rotateDisplay == 270) { g.translate(0, image.getHeight()); }
				g.rotate(Math.toRadians(Mobile.rotateDisplay));
				if(Mobile.rotateDisplay == 90 || Mobile.rotateDisplay == 270)
				{
					g.drawImage(frame, 0, 0, image.getHeight(), image.getWidth(), null);
				}
				else { g.drawImage(frame, 0, 0, image.getWidth(), image.getHeight(), null); }
			}
			g.dispose();
			ImageIO.write(image, "png", outputfile);
			Mobile.log(Mobile.LOG_INFO, ScreenShot.class.getPackage().getName() + "." + ScreenShot.class.getSimpleName() + ": " + "Saved screenshot: " + outputfile.toString());
		}
		catch (Exception e)
		{
			Mobile.log(Mobile.LOG_ERROR, ScreenShot.class.getPackage().getName() + "." + ScreenShot.class.getSimpleName() + ": " + "Error saving screenshot");
			e.printStackTrace();
		}
	}
}
