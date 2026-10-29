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

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;

import org.recompile.mobile.MemoryInspector;
import org.recompile.mobile.MemoryInspector.Entry;
import org.recompile.mobile.MemoryInspector.Reference;
import org.recompile.mobile.PlatformImage;

public final class MemoryView extends JFrame
{
	private final JCheckBox autoUpdate = new JCheckBox("Auto update", true);
	private final JSpinner interval = new JSpinner(new SpinnerNumberModel(500, 100, 60000, 100));
	private final JCheckBox autoGC = new JCheckBox("Auto GC", false);
	private final JSpinner gcInterval = new JSpinner(new SpinnerNumberModel(500, 100, 60000, 100));
	private final JLabel heapUsage = new JLabel();
	private final JTextArea statistics = new JTextArea(2, 40);
	private final JLabel totalMemoryUsed = new JLabel();
	private final JCheckBox showBuffers = new JCheckBox("Render buffers", false);
	private final JLabel imageCount = new JLabel();
	private final JLabel selectedObject = new JLabel("No object selected");
	private final JLabel imageInfo = new JLabel("No image selected");
	private final JLabel status = new JLabel();
	private final JTextArea path = new JTextArea(2, 40);
	private final DefaultTableModel classModel = new DefaultTableModel(new String[]{"Class", "Instances", "Estimated bytes", "Class bytes"}, 0)
	{
		public boolean isCellEditable(int row, int column) { return false; }
		public Class<?> getColumnClass(int column) { return column == 0 ? String.class : Long.class; }
	};
	private final JTable classTable = new JTable(classModel);
	private final DefaultListModel<Entry> instanceModel = new DefaultListModel<Entry>();
	private final DefaultListModel<Entry> imageModel = new DefaultListModel<Entry>();
	private final DefaultListModel<Reference> fieldModel = new DefaultListModel<Reference>();
	private final DefaultListModel<Reference> referenceModel = new DefaultListModel<Reference>();
	private final JList<Entry> instances = new JList<Entry>(instanceModel);
	private final JList<Entry> images = new JList<Entry>(imageModel);
	private final JList<Reference> fields = new JList<Reference>(fieldModel);
	private final JList<Reference> references = new JList<Reference>(referenceModel);
	private final Map<String, List<Entry>> classes = new TreeMap<String, List<Entry>>();
	private Map<String, Entry> staticFields = new TreeMap<String, Entry>();
	private Map<Entry, ImageIcon> thumbnails = new IdentityHashMap<Entry, ImageIcon>();
	private final List<Entry> imageEntries = new ArrayList<Entry>();
	private final ImagePreview preview = new ImagePreview();
	private final JButton saveImage = new JButton("Save PNG...");
	private final Timer timer;
	private final Timer gcTimer;
	private final AtomicBoolean gcRequested = new AtomicBoolean();
	private Entry inspected;
	private boolean refreshing;
	private boolean refreshDeferred;
	private boolean updating;

	public MemoryView(JFrame owner)
	{
		super("Memory View");
		setIconImages(owner.getIconImages());
		setDefaultCloseOperation(HIDE_ON_CLOSE);
		setSize(1100, 740);
		setMinimumSize(new Dimension(820, 600));
		setLayout(new BorderLayout(6, 6));
		getRootPane().setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JButton refresh = new JButton("Update now");
		JButton gc = new JButton("GC");
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		controls.add(autoUpdate);
		controls.add(interval);
		controls.add(new JLabel("ms"));
		controls.add(refresh);
		controls.add(autoGC);
		controls.add(gcInterval);
		controls.add(new JLabel("ms"));
		controls.add(gc);
		JPanel toolbar = new JPanel(new BorderLayout(8, 0));
		toolbar.add(controls, BorderLayout.WEST);
		toolbar.add(heapUsage, BorderLayout.EAST);
		statistics.setEditable(false);
		statistics.setOpaque(false);
		statistics.setFont(heapUsage.getFont());
		statistics.setLineWrap(true);
		statistics.setWrapStyleWord(true);
		JPanel summary = new JPanel(new BorderLayout(8, 0));
		summary.add(createPanel("Application", statistics), BorderLayout.CENTER);
		summary.add(createPanel("Total memory used", totalMemoryUsed), BorderLayout.EAST);
		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.add(toolbar, BorderLayout.NORTH);
		header.add(summary, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);

		classTable.setAutoCreateRowSorter(true);
		classTable.getColumnModel().getColumn(0).setPreferredWidth(220);
		classTable.getColumnModel().getColumn(1).setPreferredWidth(65);
		classTable.getColumnModel().getColumn(2).setPreferredWidth(110);
		classTable.getColumnModel().getColumn(3).setPreferredWidth(90);
		classTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		instances.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		images.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		path.setEditable(false);
		path.setLineWrap(true);
		path.setWrapStyleWord(true);
		fields.setToolTipText("Double-click a reference to follow it");
		references.setToolTipText("Double-click a reference to inspect its source");
		JPanel details = new JPanel(new BorderLayout(4, 6));
		details.setMinimumSize(new Dimension(240, 180));
		details.add(selectedObject, BorderLayout.NORTH);
		JSplitPane objectDetails = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
			createPanel("Fields", new JScrollPane(fields)), createPanel("Incoming references", new JScrollPane(references)));
		objectDetails.setResizeWeight(0.5);
		objectDetails.setDividerLocation(290);
		details.add(objectDetails, BorderLayout.CENTER);
		details.add(createPanel("Path to object", new JScrollPane(path)), BorderLayout.SOUTH);
		JSplitPane objectLists = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
			createPanel("Classes", new JScrollPane(classTable)), createPanel("Instances / static fields", new JScrollPane(instances)));
		objectLists.setMinimumSize(new Dimension(280, 180));
		objectLists.setResizeWeight(0.65);
		objectLists.setDividerLocation(190);
		JSplitPane objects = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectLists, details);
		objects.setResizeWeight(0.45);
		objects.setDividerLocation(480);

		images.setLayoutOrientation(JList.HORIZONTAL_WRAP);
		images.setVisibleRowCount(-1);
		images.setFixedCellWidth(88);
		images.setFixedCellHeight(88);
		images.setCellRenderer(new DefaultListCellRenderer()
		{
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused)
			{
				JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
				Entry entry = (Entry)value;
				label.setText(Integer.toHexString(entry.identity));
				label.setIcon(thumbnails.get(entry));
				label.setHorizontalAlignment(SwingConstants.CENTER);
				label.setHorizontalTextPosition(SwingConstants.CENTER);
				label.setVerticalTextPosition(SwingConstants.BOTTOM);
				label.setToolTipText(entry.toString());
				return label;
			}
		});
		references.setCellRenderer(new DefaultListCellRenderer()
		{
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused)
			{
				JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
				Reference reference = (Reference)value;
				label.setText(reference.source == null ? reference.name : reference.source.toString() + " : " + reference.name);
				return label;
			}
		});
		final JComboBox<String> zoom = new JComboBox<String>(new String[]{"25%", "50%", "100%", "200%", "400%", "800%"});
		zoom.setSelectedItem("100%");
		saveImage.setEnabled(false);
		JPanel imageControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		imageControls.add(new JLabel("Zoom"));
		imageControls.add(zoom);
		imageControls.add(saveImage);
		JPanel imagePreview = new JPanel(new BorderLayout(4, 6));
		imagePreview.setMinimumSize(new Dimension(200, 120));
		imagePreview.add(imageControls, BorderLayout.NORTH);
		imagePreview.add(new JScrollPane(preview), BorderLayout.CENTER);
		imagePreview.add(imageInfo, BorderLayout.SOUTH);
		JPanel imageFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		imageFilters.add(showBuffers);
		imageFilters.add(imageCount);
		JPanel gallery = new JPanel(new BorderLayout(4, 6));
		gallery.setMinimumSize(new Dimension(180, 120));
		gallery.add(imageFilters, BorderLayout.NORTH);
		gallery.add(new JScrollPane(images), BorderLayout.CENTER);
		JSplitPane imageArea = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createPanel("Images", gallery), createPanel("Preview", imagePreview));
		imageArea.setResizeWeight(0.6);
		imageArea.setDividerLocation(620);
		JSplitPane content = new JSplitPane(JSplitPane.VERTICAL_SPLIT, objects, imageArea);
		content.setResizeWeight(0.58);
		content.setDividerLocation(330);
		add(content, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);

		timer = new Timer(500, new ActionListener() { public void actionPerformed(ActionEvent e) { refresh(); } });
		refresh.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent e) { refresh(); } });
		ActionListener collect = new ActionListener()
		{
			public void actionPerformed(ActionEvent e) { gcRequested.set(true); refresh(); }
		};
		gcTimer = new Timer(500, collect);
		gc.addActionListener(collect);
		autoGC.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				if(autoGC.isSelected()) { gcTimer.start(); }
				else { gcTimer.stop(); }
			}
		});
		gcInterval.addChangeListener(new ChangeListener()
		{
			public void stateChanged(ChangeEvent e)
			{
				int delay = ((Integer)gcInterval.getValue()).intValue();
				gcTimer.setInitialDelay(delay);
				gcTimer.setDelay(delay);
			}
		});
		autoUpdate.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				if(autoUpdate.isSelected()) { timer.start(); }
				else { timer.stop(); }
			}
		});
		interval.addChangeListener(new ChangeListener()
		{
			public void stateChanged(ChangeEvent e) { timer.setDelay(((Integer)interval.getValue()).intValue()); }
		});
		ListSelectionListener selectionListener = new ListSelectionListener()
		{
			public void valueChanged(ListSelectionEvent e)
			{
				if(updating || e.getValueIsAdjusting()) { return; }
				Object source = e.getSource();
				if(source == classTable.getSelectionModel()) { updateInstances(null); }
				else if(source == instances.getSelectionModel()) { inspect(instances.getSelectedValue()); }
				else if(source == images.getSelectionModel()) { inspect(images.getSelectedValue()); }
				if(refreshDeferred && !isSelectionAdjusting()) { refresh(); }
			}
		};
		classTable.getSelectionModel().addListSelectionListener(selectionListener);
		instances.getSelectionModel().addListSelectionListener(selectionListener);
		images.getSelectionModel().addListSelectionListener(selectionListener);
		fields.getSelectionModel().addListSelectionListener(selectionListener);
		references.getSelectionModel().addListSelectionListener(selectionListener);
		fields.addMouseListener(new MouseAdapter()
		{
			public void mouseClicked(MouseEvent e)
			{
				Reference reference = fields.getSelectedValue();
				if(e.getClickCount() == 2 && reference != null && reference.target != null) { inspect(reference.target); }
			}
		});
		references.addMouseListener(new MouseAdapter()
		{
			public void mouseClicked(MouseEvent e)
			{
				Reference reference = references.getSelectedValue();
				if(e.getClickCount() == 2 && reference != null && reference.source != null) { inspect(reference.source); }
			}
		});
		zoom.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				String value = (String)zoom.getSelectedItem();
				preview.zoom = Integer.parseInt(value.substring(0, value.length() - 1));
				preview.updateSize();
			}
		});
		saveImage.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent e) { saveImage(); } });
		showBuffers.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent e) { updateImages(images.getSelectedValue()); } });
		addComponentListener(new ComponentAdapter()
		{
			public void componentShown(ComponentEvent e)
			{
				refresh();
				if(autoUpdate.isSelected()) { timer.start(); }
				if(autoGC.isSelected()) { gcTimer.start(); }
			}
			public void componentHidden(ComponentEvent e)
			{
				gcRequested.set(false);
				refreshDeferred = false;
				timer.stop();
				gcTimer.stop();
				updating = true;
				classModel.setRowCount(0);
				instanceModel.clear();
				imageModel.clear();
				classes.clear();
				staticFields.clear();
				thumbnails.clear();
				imageEntries.clear();
				inspect(null);
				updating = false;
			}
		});
	}

	public void positionNextTo(Window owner)
	{
		GraphicsConfiguration configuration = owner.getGraphicsConfiguration();
		Rectangle screen = new Rectangle(configuration.getBounds());
		Insets insets = getToolkit().getScreenInsets(configuration);
		screen.x += insets.left;
		screen.y += insets.top;
		screen.width -= insets.left + insets.right;
		screen.height -= insets.top + insets.bottom;
		Rectangle bounds = owner.getBounds();
		int gap = 8;
		int left = Math.max(0, bounds.x - screen.x - gap);
		int right = Math.max(0, screen.x + screen.width - bounds.x - bounds.width - gap);
		boolean rightSide = right >= getWidth() || (left < getWidth() && right >= left);
		int available = rightSide ? right : left;
		int width = getWidth();
		if(available >= getMinimumSize().width) { width = Math.min(width, available); }
		else
		{
			width = Math.min(width, Math.max(getMinimumSize().width, screen.width - bounds.width - gap));
			if(width + bounds.width + gap <= screen.width)
			{
				bounds.x = rightSide ? Math.min(bounds.x, screen.x + screen.width - bounds.width - width - gap) :
					Math.max(bounds.x, screen.x + width + gap);
				owner.setLocation(bounds.x, bounds.y);
			}
		}
		setSize(width, Math.min(getHeight(), screen.height));
		int x = rightSide ? bounds.x + bounds.width + gap : bounds.x - getWidth() - gap;
		x = Math.max(screen.x, Math.min(x, screen.x + screen.width - getWidth()));
		int y = Math.max(screen.y, Math.min(bounds.y, screen.y + screen.height - getHeight()));
		setLocation(x, y);
	}

	private JPanel createPanel(String title, Component component)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createTitledBorder(title));
		panel.add(component, BorderLayout.CENTER);
		return panel;
	}

	private boolean isSelectionAdjusting()
	{
		return classTable.getSelectionModel().getValueIsAdjusting() || instances.getValueIsAdjusting() ||
			images.getValueIsAdjusting() || fields.getValueIsAdjusting() || references.getValueIsAdjusting();
	}

	private void refresh()
	{
		if(refreshing || !isVisible()) { return; }
		if(isSelectionAdjusting()) { refreshDeferred = true; return; }
		refreshDeferred = false;
		refreshing = true;
		Thread worker = new Thread(new Runnable()
		{
			public void run()
			{
				try
				{
					if(gcRequested.getAndSet(false)) { System.gc(); }
					final MemoryInspector.Snapshot snapshot = MemoryInspector.snapshot();
					final Map<Entry, ImageIcon> icons = new IdentityHashMap<Entry, ImageIcon>();
					for(Entry entry : snapshot.entries)
					{
						BufferedImage source = MemoryInspector.getImage(entry);
						if(source == null) { continue; }
						icons.put(entry, createThumbnail(source));
					}
					for(Entry entry : snapshot.renderBuffers)
					{
						BufferedImage source = MemoryInspector.getImage(entry);
						if(source != null) { icons.put(entry, createThumbnail(source)); }
					}
					SwingUtilities.invokeLater(new Runnable()
					{
						public void run()
						{
							try
							{
								if(isVisible())
								{
									if(isSelectionAdjusting()) { refreshDeferred = true; }
									else { update(snapshot, icons); }
								}
							}
							catch(Exception e) { status.setText("Refresh failed: " + e.getMessage()); }
							finally
							{
								updating = false;
								refreshing = false;
								if(gcRequested.get()) { refresh(); }
							}
						}
					});
				}
				catch(final Exception e)
				{
					SwingUtilities.invokeLater(new Runnable()
					{
						public void run()
						{
							status.setText("Refresh failed: " + e.getMessage());
							refreshing = false;
							if(gcRequested.get()) { refresh(); }
						}
					});
				}
			}
		}, "Memory-View-Refresh");
		worker.setDaemon(true);
		worker.start();
	}

	private void update(MemoryInspector.Snapshot snapshot, Map<Entry, ImageIcon> icons)
	{
		String selectedClass = getSelectedClass();
		Entry selected = findEntry(inspected, snapshot);
		Entry selectedInstance = findEntry(instances.getSelectedValue(), snapshot);
		Entry selectedImage = findEntry(images.getSelectedValue(), snapshot);
		updating = true;
		classes.clear();
		staticFields = snapshot.staticFields;
		for(String name : snapshot.classBytes.keySet()) { classes.put(name, new ArrayList<Entry>()); }
		for(Entry entry : snapshot.entries)
		{
			List<Entry> values = classes.get(entry.className);
			if(values == null) { values = new ArrayList<Entry>(); classes.put(entry.className, values); }
			values.add(entry);
		}
		classModel.setRowCount(0);
		for(Map.Entry<String, List<Entry>> type : classes.entrySet())
		{
			long bytes = 0;
			Entry classFields = staticFields.get(type.getKey());
			if(classFields != null) { bytes += classFields.estimatedBytes; }
			for(Entry entry : type.getValue()) { bytes += entry.estimatedBytes; }
			Integer classBytes = snapshot.classBytes.get(type.getKey());
			classModel.addRow(new Object[]{type.getKey(), Long.valueOf(type.getValue().size()), Long.valueOf(bytes),
				Long.valueOf(classBytes == null ? 0 : classBytes.intValue())});
		}
		if(selectedClass == null && classModel.getRowCount() > 0) { selectedClass = (String)classModel.getValueAt(0, 0); }
		for(int i = 0; i < classModel.getRowCount(); i++)
		{
			if(classModel.getValueAt(i, 0).equals(selectedClass))
			{
				int row = classTable.convertRowIndexToView(i);
				classTable.setRowSelectionInterval(row, row);
				break;
			}
		}
		thumbnails = icons;
		if(classTable.getSelectedRow() < 0 && classModel.getRowCount() > 0) { classTable.setRowSelectionInterval(0, 0); }
		imageEntries.clear();
		imageEntries.addAll(icons.keySet());
		Collections.sort(imageEntries, new Comparator<Entry>()
		{
			public int compare(Entry first, Entry second)
			{
				int name = first.className.compareTo(second.className);
				if(name != 0) { return name; }
				return first.identity == second.identity ? 0 : first.identity < second.identity ? -1 : 1;
			}
		});
		updateImages(selectedImage);
		updateInstances(selectedInstance);
		inspect(selected);
		Runtime runtime = Runtime.getRuntime();
		heapUsage.setText("JVM heap: " + ((runtime.totalMemory() - runtime.freeMemory()) / 1024) + " / " + (runtime.maxMemory() / 1024) + " KiB");
		long total = snapshot.estimatedBytes;
		totalMemoryUsed.setText(String.format(Locale.US, "%.1f KiB (%s)", total / 1024.0,
			snapshot.unreadableFields == 0 ? "estimate" : "incomplete estimate"));
		totalMemoryUsed.setToolTipText("Estimated storage reachable from application roots, including stream buffers and media data: " +
			total + " bytes; excludes class data, emulator render buffers and host audio devices");
		statistics.setText("Loaded classes: " + snapshot.classBytes.size() + "   Class data: " + snapshot.loadedClassBytes +
			" bytes\nObjects: " + snapshot.entries.size() + "   Estimated storage: " + snapshot.estimatedBytes +
			" bytes");
		status.setText("Unreadable fields: " + snapshot.unreadableFields);
		updating = false;
	}

	private static boolean isSameObject(Entry first, Entry second)
	{
		if(first == null || second == null) { return first == second; }
		if(first.staticFields || second.staticFields)
		{
			return first.staticFields && second.staticFields && first.className.equals(second.className);
		}
		Object object = first.object.get();
		return object != null && object == second.object.get();
	}

	private static Entry findEntry(Entry previous, MemoryInspector.Snapshot snapshot)
	{
		if(previous == null) { return null; }
		if(previous.staticFields) { return snapshot.staticFields.get(previous.className); }
		Object object = previous.object.get();
		if(object == null) { return null; }
		for(Entry entry : snapshot.entries) { if(entry.object.get() == object) { return entry; } }
		for(Entry entry : snapshot.renderBuffers) { if(entry.object.get() == object) { return entry; } }
		return null;
	}

	private static ImageIcon createThumbnail(BufferedImage source)
	{
		double scale = Math.min(1.0, Math.min(64.0 / source.getWidth(), 64.0 / source.getHeight()));
		BufferedImage thumbnail = new BufferedImage(Math.max(1, (int)(source.getWidth() * scale)),
			Math.max(1, (int)(source.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = thumbnail.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(source, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
		g.dispose();
		return new ImageIcon(thumbnail);
	}

	private void updateImages(Entry selected)
	{
		boolean wasUpdating = updating;
		updating = true;
		imageModel.clear();
		for(Entry entry : imageEntries)
		{
			if(showBuffers.isSelected() || !entry.className.equals(PlatformImage.class.getName())) { imageModel.addElement(entry); }
		}
		if(selected != null) { images.setSelectedValue(selected, false); }
		imageCount.setText(imageModel.size() + " / " + imageEntries.size() + " images");
		updating = wasUpdating;
	}

	private String getSelectedClass()
	{
		int row = classTable.getSelectedRow();
		return row < 0 ? null : (String)classModel.getValueAt(classTable.convertRowIndexToModel(row), 0);
	}

	private void updateInstances(Entry selected)
	{
		instanceModel.clear();
		String name = getSelectedClass();
		List<Entry> values = name == null ? null : classes.get(name);
		Entry classFields = name == null ? null : staticFields.get(name);
		if(classFields != null) { instanceModel.addElement(classFields); }
		if(values != null) { for(Entry entry : values) { instanceModel.addElement(entry); } }
		if(selected != null) { instances.setSelectedValue(selected, false); }
	}

	private void inspect(Entry entry)
	{
		boolean keepSelection = isSameObject(inspected, entry);
		Reference selectedField = keepSelection ? fields.getSelectedValue() : null;
		Reference selectedReference = keepSelection ? references.getSelectedValue() : null;
		inspected = entry;
		fieldModel.clear();
		referenceModel.clear();
		selectedObject.setText(entry == null ? "No object selected" : entry.toString() + "   " + entry.estimatedBytes + " bytes (estimate)");
		path.setText(entry == null ? "" : entry.getPath());
		preview.image = null;
		imageInfo.setText("No image selected");
		saveImage.setEnabled(false);
		if(entry != null)
		{
			for(Reference field : MemoryInspector.getFields(entry))
			{
				fieldModel.addElement(field);
				if(selectedField != null && field.name.equals(selectedField.name)) { fields.setSelectedValue(field, false); }
			}
			for(Reference reference : entry.incoming)
			{
				referenceModel.addElement(reference);
				if(selectedReference != null && reference.name.equals(selectedReference.name) &&
					isSameObject(reference.source, selectedReference.source)) { references.setSelectedValue(reference, false); }
			}
			Object object = entry.object.get();
			BufferedImage image = MemoryInspector.getImage(entry);
			if(image != null)
			{
				preview.image = image;
				thumbnails.put(entry, createThumbnail(image));
				images.repaint();
				String type = entry.className;
				if(object instanceof PlatformImage)
				{
					type = object.getClass() == PlatformImage.class ? "Render buffer" :
						object instanceof com.nttdocomo.ui.Image ? "DoJa" : "LCDUI";
					type += ((PlatformImage)object).isMutable() ? " / Mutable" : " / Immutable";
				}
				else if(object instanceof javax.microedition.m3g.Image2D) { type = "M3G"; }
				else if(object instanceof com.mascotcapsule.micro3d.v3.Texture) { type = "MascotCapsule"; }
				imageInfo.setText(image.getWidth() + " x " + image.getHeight() + "   " + type);
				saveImage.setEnabled(true);
			}
		}
		preview.updateSize();
	}

	private void saveImage()
	{
		BufferedImage image = preview.image;
		if(image == null) { return; }
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new File("image-" + Integer.toHexString(inspected.identity) + ".png"));
		if(chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
		File file = chooser.getSelectedFile();
		if(!file.getName().toLowerCase().endsWith(".png")) { file = new File(file.getPath() + ".png"); }
		if(file.exists() && JOptionPane.showConfirmDialog(this, "Replace existing file?", "Save Image", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) { return; }
		try { ImageIO.write(image, "png", file); }
		catch(Exception e) { JOptionPane.showMessageDialog(this, e.getMessage(), "Save Image", JOptionPane.ERROR_MESSAGE); }
	}

	private static final class ImagePreview extends JPanel
	{
		private BufferedImage image;
		private int zoom = 100;

		private void updateSize()
		{
			setPreferredSize(image == null ? new Dimension(1, 1) :
				new Dimension(Math.max(1, image.getWidth() * zoom / 100), Math.max(1, image.getHeight() * zoom / 100)));
			revalidate();
			repaint();
		}

		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Rectangle clip = g.getClipBounds();
			for(int y = clip.y / 8 * 8; y < clip.y + clip.height; y += 8)
			{
				for(int x = clip.x / 8 * 8; x < clip.x + clip.width; x += 8)
				{
					g.setColor(((x / 8 + y / 8) & 1) == 0 ? Color.WHITE : Color.LIGHT_GRAY);
					g.fillRect(x, y, 8, 8);
				}
			}
			if(image != null)
			{
				((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				Dimension size = getPreferredSize();
				g.drawImage(image, 0, 0, size.width, size.height, null);
			}
		}
	}
}
