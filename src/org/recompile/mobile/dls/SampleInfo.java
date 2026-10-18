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

/** Loop, tuning, and attenuation metadata for a region or wave. */
final class SampleInfo
{
	boolean present;
	int loopMode = SynthesisSupport.LOOP_NONE;
	int unityNote = 60;
	int fineTuneCents = 0;
	int attenuation = 0;
	int loopStart = 0;
	int loopEndInclusive = -1;
	boolean loopUntilRelease;
}
