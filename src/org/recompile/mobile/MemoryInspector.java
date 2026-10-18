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

import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

public final class MemoryInspector
{
	private static final WeakHashMap<Class<?>, Integer> classes = new WeakHashMap<Class<?>, Integer>();
	private static final WeakHashMap<Class<?>, Boolean> initialized = new WeakHashMap<Class<?>, Boolean>();

	public static synchronized void registerClass(Class<?> type, int bytes)
	{
		classes.put(type, Integer.valueOf(bytes));
	}

	public static synchronized void classInitialized(Class<?> type)
	{
		initialized.put(type, Boolean.TRUE);
	}

	public static final class Snapshot
	{
		public final List<Entry> entries = new ArrayList<Entry>();
		public final List<Entry> renderBuffers = new ArrayList<Entry>();
		public final Map<String, Integer> classBytes = new TreeMap<String, Integer>();
		public final Map<String, Entry> staticFields = new TreeMap<String, Entry>();
		public long estimatedBytes;
		public long loadedClassBytes;
		public int unreadableFields;
	}

	public static final class Entry
	{
		public final WeakReference<Object> object;
		public final String className;
		public final int identity;
		public final boolean staticFields;
		public final List<Reference> fields = new ArrayList<Reference>();
		public final List<Reference> incoming = new ArrayList<Reference>();
		public long estimatedBytes = 16;
		private Reference parent;

		private Entry(Object value)
		{
			this(value, false);
		}

		private Entry(Object value, boolean isStatic)
		{
			object = new WeakReference<Object>(value);
			className = isStatic ? ((Class<?>)value).getName() : getClassName(value.getClass());
			identity = System.identityHashCode(value);
			staticFields = isStatic;
			if(isStatic) { estimatedBytes = 0; }
		}

		public String toString() { return staticFields ? className + " (static fields)" : className + "@" + Integer.toHexString(identity); }

		public String getPath()
		{
			List<String> parts = new ArrayList<String>();
			Entry current = this;
			while(current.parent != null)
			{
				parts.add(current.parent.name);
				current = current.parent.source;
				if(current == null) { break; }
			}
			StringBuilder path = new StringBuilder();
			if(current != null)
			{
				if(current.staticFields) { path.append("static ").append(current.className); }
				else { path.append(current.toString()); }
			}
			for(int i = parts.size() - 1; i >= 0; i--)
			{
				String part = parts.get(i);
				if(path.length() > 0) { path.append(" -> "); }
				path.append(part);
			}
			return path.toString();
		}
	}

	public static final class Reference
	{
		public final Entry source;
		public final Entry target;
		public final String name;
		public final String value;

		private Reference(Entry source, Entry target, String name, String value)
		{
			this.source = source;
			this.target = target;
			this.name = name;
			this.value = value;
		}

		public String toString() { return name + " = " + (target == null ? value : target.toString()); }
	}

	public static List<Reference> getFields(Entry entry)
	{
		Object object = entry.object.get();
		if(object == null || !object.getClass().isArray() || !object.getClass().getComponentType().isPrimitive()) { return entry.fields; }
		List<Reference> fields = new ArrayList<Reference>(entry.fields);
		// Read primitive values only for the selected array, rather than every pixel/audio buffer.
		int length = Array.getLength(object);
		for(int i = 0; i < Math.min(length, 64); i++)
		{
			fields.add(new Reference(entry, null, "[" + i + "]", String.valueOf(Array.get(object, i))));
		}
		if(length > 64) { fields.add(new Reference(entry, null, "preview", "First 64 elements")); }
		return fields;
	}

	public static BufferedImage getImage(Entry entry)
	{
		Object object = entry.object.get();
		if(object instanceof PlatformImage)
		{
			if(object instanceof com.nttdocomo.ui.Image && ((com.nttdocomo.ui.Image)object).isDisposed()) { return null; }
			synchronized(object)
			{
				BufferedImage image = ((PlatformImage)object).getCanvas();
				if(image == null) { return null; }
				return new BufferedImage(image.getColorModel(), image.copyData(null), image.isAlphaPremultiplied(), null);
			}
		}
		if(object instanceof javax.microedition.m3g.Image2D) { return ((javax.microedition.m3g.Image2D)object).createDebugImage(); }
		if(object instanceof com.mascotcapsule.micro3d.v3.Texture) { return ((com.mascotcapsule.micro3d.v3.Texture)object).createDebugImage(); }
		return null;
	}

	public static Snapshot snapshot()
	{
		Snapshot snapshot = new Snapshot();
		MIDletLoader loader = Mobile.getPlatform().loader;
		List<Class<?>> staticClasses = new ArrayList<Class<?>>();
		synchronized(MemoryInspector.class)
		{
			for(Map.Entry<Class<?>, Integer> entry : classes.entrySet())
			{
				if(entry.getKey().getClassLoader() != loader) { continue; }
				snapshot.classBytes.put(entry.getKey().getName(), entry.getValue());
				snapshot.loadedClassBytes += entry.getValue().intValue();
			}
			for(Class<?> type : initialized.keySet()) { if(type.getClassLoader() == loader) { staticClasses.add(type); } }
		}

		IdentityHashMap<Object, Entry> seen = new IdentityHashMap<Object, Entry>();
		List<Object> pending = new ArrayList<Object>();
		if(loader != null) { addReference(snapshot, seen, pending, null, "Application", loader.getApplication()); }
		if(Mobile.isDoJa) { addReference(snapshot, seen, pending, null, "Display", com.nttdocomo.ui.Display.getCurrent()); }
		else if(Mobile.getDisplay() != null) { addReference(snapshot, seen, pending, null, "Display", Mobile.getDisplay().getCurrent()); }
		for(Class<?> type : staticClasses)
		{
			Entry entry = new Entry(type, true);
			snapshot.staticFields.put(type.getName(), entry);
			readFields(snapshot, seen, pending, entry, type, null);
			snapshot.estimatedBytes += entry.estimatedBytes;
		}
		// Only objects reachable from application roots belong in this estimate.
		for(int i = 0; i < pending.size(); i++) { scanObject(snapshot, seen, pending, pending.get(i)); }
		for(Entry entry : snapshot.entries) { snapshot.estimatedBytes += entry.estimatedBytes; }
		PlatformImage front = Mobile.getPlatform().getLcdFrontbuffer();
		PlatformImage back = MobilePlatform.getLcdBackbuffer();
		if(front != null) { snapshot.renderBuffers.add(new Entry(front)); }
		if(back != null && back != front) { snapshot.renderBuffers.add(new Entry(back)); }
		return snapshot;
	}

	private static void addReference(Snapshot snapshot, IdentityHashMap<Object, Entry> seen, List<Object> pending, Entry source, String name, Object value)
	{
		Entry target = null;
		// Emulator implementation objects are not part of the application's heap.
		if(value != null && !getClassName(value.getClass()).startsWith("org.recompile."))
		{
			target = seen.get(value);
			if(target == null)
			{
				target = new Entry(value);
				seen.put(value, target);
				pending.add(value);
				snapshot.entries.add(target);
			}
		}
		Reference reference = new Reference(source, target, name, value == null ? "null" : "<emulator internal>");
		if(source != null) { source.fields.add(reference); }
		if(target != null)
		{
			target.incoming.add(reference);
			if(target.parent == null) { target.parent = reference; }
		}
	}

	private static void scanObject(Snapshot snapshot, IdentityHashMap<Object, Entry> seen, List<Object> pending, Object object)
	{
		Entry entry = seen.get(object);
		Class<?> type = object.getClass();
		if(type.isArray())
		{
			int length = Array.getLength(object);
			Class<?> component = type.getComponentType();
			entry.estimatedBytes += (long)length * fieldSize(component);
			entry.fields.add(new Reference(entry, null, "length", Integer.toString(length)));
			if(!component.isPrimitive())
			{
				for(int i = 0; i < length; i++)
				{
					Object value = Array.get(object, i);
					if(value != null) { addReference(snapshot, seen, pending, entry, "[" + i + "]", value); }
				}
			}
		}
		else if(object instanceof String)
		{
			entry.estimatedBytes += 2L * ((String)object).length();
			String text = (String)object;
			int length = Math.min(text.length(), 256);
			char[] preview = new char[length];
			text.getChars(0, length, preview, 0);
			entry.fields.add(new Reference(entry, null, "value", new String(preview) + (text.length() > length ? "..." : "")));
		}
		else if(type == Boolean.class || type == Character.class || type == Byte.class || type == Short.class ||
			type == Integer.class || type == Long.class || type == Float.class || type == Double.class)
		{
			entry.estimatedBytes += type == Long.class || type == Double.class ? 8 : 4;
			entry.fields.add(new Reference(entry, null, "value", String.valueOf(object)));
		}
		else if(object instanceof PlatformImage)
		{
			// Count the game's logical image, not AWT storage or the high-resolution copy.
			BufferedImage image = ((PlatformImage)object).getCanvas();
			if(image != null && !(object instanceof com.nttdocomo.ui.Image && ((com.nttdocomo.ui.Image)object).isDisposed()))
			{
				entry.estimatedBytes += 4L * image.getWidth() * image.getHeight();
				entry.fields.add(new Reference(entry, null, "width", Integer.toString(image.getWidth())));
				entry.fields.add(new Reference(entry, null, "height", Integer.toString(image.getHeight())));
			}
		}
		else if(object instanceof Collection)
		{
			Object[] values = ((Collection<?>)object).toArray();
			entry.estimatedBytes += 4L * values.length;
			for(int i = 0; i < values.length; i++) { addReference(snapshot, seen, pending, entry, "[" + i + "]", values[i]); }
		}
		else if(object instanceof Map)
		{
			synchronized(object)
			{
				int index = 0;
				for(Map.Entry<?, ?> value : ((Map<?, ?>)object).entrySet())
				{
					addReference(snapshot, seen, pending, entry, "key[" + index + "]", value.getKey());
					addReference(snapshot, seen, pending, entry, "value[" + index + "]", value.getValue());
					index++;
				}
				entry.estimatedBytes += 8L * index;
			}
		}
		for(Class<?> current = type; current != null; current = current.getSuperclass())
		{
			// Stop at the host implementation boundary, including shared platform base classes.
			if(current.getName().startsWith("org.recompile.") || current.getName().startsWith("java.") ||
				current.getName().startsWith("sun.") || current.getName().startsWith("jdk.")) { break; }
			readFields(snapshot, seen, pending, entry, current, object);
		}
	}

	private static void readFields(Snapshot snapshot, IdentityHashMap<Object, Entry> seen, List<Object> pending, Entry entry, Class<?> type, Object object)
	{
		try
		{
			for(Field field : type.getDeclaredFields())
			{
				if(Modifier.isStatic(field.getModifiers()) != (object == null)) { continue; }
				String name = type.getName() + "." + field.getName();
				entry.estimatedBytes += fieldSize(field.getType());
				try
				{
					field.setAccessible(true);
					Object value = field.get(object);
					if(field.getType().isPrimitive())
					{
						entry.fields.add(new Reference(entry, null, name, String.valueOf(value)));
					}
					else { addReference(snapshot, seen, pending, entry, name, value); }
				}
				catch(Exception e)
				{
					snapshot.unreadableFields++;
					entry.fields.add(new Reference(entry, null, name, "<unavailable>"));
				}
			}
		}
		catch(LinkageError e) { snapshot.unreadableFields++; }
		catch(SecurityException e) { snapshot.unreadableFields++; }
	}

	private static int fieldSize(Class<?> type)
	{
		// A 32-bit object model for comparison; this is not the host JVM's object layout.
		if(type == Boolean.TYPE || type == Byte.TYPE) { return 1; }
		if(type == Character.TYPE || type == Short.TYPE) { return 2; }
		if(type == Long.TYPE || type == Double.TYPE) { return 8; }
		return 4;
	}

	private static String getClassName(Class<?> type)
	{
		String suffix = "";
		while(type.isArray())
		{
			suffix += "[]";
			type = type.getComponentType();
		}
		return type.getName() + suffix;
	}
}
