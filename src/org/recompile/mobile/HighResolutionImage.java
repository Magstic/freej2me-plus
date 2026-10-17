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
package org.recompile.mobile;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/* A presentation surface only: MIDlet dimensions, metrics and pixel reads stay unchanged. */
final class HighResolutionImage
{
	static final int SCALE = 4;

	final BufferedImage canvas;
	private final int width;
	private final int stride;
	private final boolean opaque;
	private final int[] pixels;
	private final int[] logicalPixels;
	private final int[] expectedPixels;

	HighResolutionImage(BufferedImage source)
	{
		width = source.getWidth();
		stride = width * SCALE;
		opaque = source.getType() == BufferedImage.TYPE_INT_RGB;
		canvas = new BufferedImage(stride, source.getHeight() * SCALE, BufferedImage.TYPE_INT_ARGB);
		pixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
		logicalPixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
		expectedPixels = (int[]) logicalPixels.clone();
		Graphics2D g = canvas.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(source, 0, 0, canvas.getWidth(), canvas.getHeight(), null);
		g.dispose();
	}

	/* Account for native/3D renderers that write directly to the logical framebuffer. */
	void syncPixels()
	{
		for(int i = 0; i < logicalPixels.length; i++)
		{
			if(logicalPixels[i] != expectedPixels[i]) { setPixel(i, logicalPixels[i]); }
		}
	}

	void setPixel(int index, int color)
	{
		if(opaque) { color |= 0xFF000000; }
		expectedPixels[index] = logicalPixels[index];
		int start = (index / width) * SCALE * stride + (index % width) * SCALE;
		for(int y = 0; y < SCALE; y++) { Arrays.fill(pixels, start + y * stride, start + y * stride + SCALE, color); }
	}

	void blendPixel(int index, int color, PlatformGraphics graphics)
	{
		expectedPixels[index] = logicalPixels[index];
		int start = (index / width) * SCALE * stride + (index % width) * SCALE;
		for(int y = 0; y < SCALE; y++)
		{
			for(int x = 0; x < SCALE; x++)
			{
				int dest = start + y * stride + x;
				pixels[dest] = graphics.blendPixels(color, pixels[dest]) | (opaque ? 0xFF000000 : 0);
			}
		}
	}

	void blendImagePixel(int index, HighResolutionImage source, int sourceIndex, PlatformGraphics graphics)
	{
		expectedPixels[index] = logicalPixels[index];
		int dest = (index / width) * SCALE * stride + (index % width) * SCALE;
		int src = (sourceIndex / source.width) * SCALE * source.stride + (sourceIndex % source.width) * SCALE;
		for(int y = 0; y < SCALE; y++)
		{
			for(int x = 0; x < SCALE; x++)
			{
				int color = source.pixels[src + y * source.stride + x];
				int offset = dest + y * stride + x;
				pixels[offset] = graphics.blendPixels(color, pixels[offset]) | (opaque ? 0xFF000000 : 0);
			}
		}
	}

	void fillRect(int x, int y, int w, int h, int color)
	{
		if(opaque) { color |= 0xFF000000; }
		for(int row = y * SCALE; row < (y + h) * SCALE; row++) { Arrays.fill(pixels, row * stride + x * SCALE, row * stride + (x + w) * SCALE, color); }
		for(int row = y; row < y + h; row++)
		{
			System.arraycopy(logicalPixels, row * width + x, expectedPixels, row * width + x, w);
		}
	}

	private Graphics2D createGraphics(Graphics2D logical)
	{
		Graphics2D g = canvas.createGraphics();
		g.scale(SCALE, SCALE);
		g.transform(logical.getTransform());
		g.setClip(logical.getClip());
		g.setColor(logical.getColor());
		g.setComposite(logical.getComposite());
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		return g;
	}

	Rectangle drawText(Graphics2D logical, String text, int x, int y)
	{
		syncPixels();
		Graphics2D g = createGraphics(logical);
		// Lay out at the original size so hinting at a larger size cannot change character spacing.
		TextLayout layout = new TextLayout(text, logical.getFont(), logical.getFontRenderContext());
		layout.draw(g, x, y);
		g.dispose();
		Rectangle bounds = layout.getPixelBounds(logical.getFontRenderContext(), x, y);
		bounds.translate((int) logical.getTransform().getTranslateX(), (int) logical.getTransform().getTranslateY());
		bounds.grow(2, 2);
		return bounds.intersection(new Rectangle(0, 0, width, canvas.getHeight() / SCALE));
	}

	void fillOverlay(Graphics2D logical, int width, int height)
	{
		syncPixels();
		Graphics2D g = createGraphics(logical);
		g.fillRect(0, 0, width, height);
		g.dispose();
	}

	void copyRegion(HighResolutionImage source, int sx, int sy, int w, int h, int dx, int dy)
	{
		// Snapshot first: copyArea and framebuffer copies can overlap.
		BufferedImage copy = new BufferedImage(w * SCALE, h * SCALE, canvas.getType());
		Graphics2D g = copy.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(source.canvas, -sx * SCALE, -sy * SCALE, null);
		g.dispose();
		g = canvas.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(copy, dx * SCALE, dy * SCALE, null);
		g.dispose();
		Rectangle dest = new Rectangle(dx, dy, w, h).intersection(new Rectangle(0, 0, width, canvas.getHeight() / SCALE));
		if(opaque)
		{
			for(int y = dest.y * SCALE; y < (dest.y + dest.height) * SCALE; y++)
			{
				for(int x = dest.x * SCALE; x < (dest.x + dest.width) * SCALE; x++) { pixels[y * stride + x] |= 0xFF000000; }
			}
		}
		captureRegion(dest);
	}

	void replaceRegion(int x, int y, int w, int h)
	{
		int right = Math.min(width, x + w);
		int bottom = Math.min(canvas.getHeight() / SCALE, y + h);
		for(int row = Math.max(0, y); row < bottom; row++)
		{
			for(int col = Math.max(0, x); col < right; col++) { setPixel(row * width + col, logicalPixels[row * width + col]); }
		}
	}

	void flushRegion(HighResolutionImage source, int x, int y, int w, int h, int mask, int[] overlay, PlatformGraphics graphics)
	{
		if(source == null) { replaceRegion(x, y, w, h); return; }
		for(int row = y * SCALE; row < (y + h) * SCALE; row++)
		{
			int start = row * stride + x * SCALE;
			int src = row * source.stride + x * SCALE;
			if(mask == 0xFFFFFFFF && overlay == null && (!opaque || source.opaque)) { System.arraycopy(source.pixels, src, pixels, start, w * SCALE); }
			else
			{
				for(int col = 0; col < w * SCALE; col++)
				{
					int color = source.pixels[src + col] & mask;
					if(overlay != null) { color = graphics.blendPixels(overlay[(row / SCALE) * source.width + x + col / SCALE], color); }
					pixels[start + col] = color | (opaque ? 0xFF000000 : 0);
				}
			}
		}
		for(int row = y; row < y + h; row++) { System.arraycopy(logicalPixels, row * width + x, expectedPixels, row * width + x, w); }
	}

	void drawRegion(HighResolutionImage source, int dx, int dy, int w, int h, int sx, int sy, int sw, int sh, int transform, Rectangle clip, PlatformGraphics graphics)
	{
		boolean rotate = (transform == PlatformGraphics.FLIP_ROTATE_LEFT ||
			transform == PlatformGraphics.FLIP_ROTATE_RIGHT ||
			transform == PlatformGraphics.FLIP_ROTATE_RIGHT_VERTICAL ||
			transform == PlatformGraphics.FLIP_ROTATE_RIGHT_HORIZONTAL);
		int sourceWidth = (rotate ? sh : sw) * SCALE;
		int sourceHeight = (rotate ? sw : sh) * SCALE;
		Rectangle dest = new Rectangle(dx, dy, w, h).intersection(clip);
		for(int y = dest.y * SCALE; y < (dest.y + dest.height) * SCALE; y++)
		{
			int baseY = (int) (((2L * (y - dy * SCALE) + 1) * sourceHeight) / (2L * h * SCALE));
			for(int x = dest.x * SCALE; x < (dest.x + dest.width) * SCALE; x++)
			{
				int baseX = (int) (((2L * (x - dx * SCALE) + 1) * sourceWidth) / (2L * w * SCALE));
				int src = sy * SCALE * source.stride + sx * SCALE + PlatformImage.getTransformedPixelIndex(
					baseX, baseY, sw * SCALE, sh * SCALE, source.stride, transform);
				int offset = y * stride + x;
				pixels[offset] = graphics.blendPixels(source.pixels[src], pixels[offset]) | (opaque ? 0xFF000000 : 0);
			}
		}
		captureRegion(dest);
	}

	void drawAffine(HighResolutionImage source, int sx, int sy, int sw, int sh, int inv00, int inv01, int inv02, int inv10, int inv11, int inv12, Rectangle dest, PlatformGraphics graphics)
	{
		for(int y = dest.y * SCALE; y < (dest.y + dest.height) * SCALE; y++)
		{
			for(int x = dest.x * SCALE; x < (dest.x + dest.width) * SCALE; x++)
			{
				int srcX = (int) (((long) inv00 * x + (long) inv01 * y + (long) inv02 * SCALE) >> 12);
				int srcY = (int) (((long) inv10 * x + (long) inv11 * y + (long) inv12 * SCALE) >> 12);
				if(srcX < 0 || srcY < 0 || srcX >= sw * SCALE || srcY >= sh * SCALE) { continue; }
				srcX += sx * SCALE;
				srcY += sy * SCALE;
				if(srcX < 0 || srcY < 0 || srcX >= source.canvas.getWidth() || srcY >= source.canvas.getHeight()) { continue; }
				int offset = y * stride + x;
				pixels[offset] = graphics.blendPixels(source.pixels[srcY * source.stride + srcX], pixels[offset]) | (opaque ? 0xFF000000 : 0);
			}
		}
		captureRegion(dest);
	}

	void captureRegion(Rectangle bounds)
	{
		if(bounds.isEmpty()) { return; }
		for(int row = bounds.y; row < bounds.y + bounds.height; row++)
		{
			System.arraycopy(logicalPixels, row * width + bounds.x, expectedPixels, row * width + bounds.x, bounds.width);
		}
	}
}
