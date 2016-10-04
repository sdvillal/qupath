/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.gui.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.ViewerManager;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/**
 * Create an annotation that covers the entire image.
 * 
 * @author Pete Bankhead
 *
 */
public class SelectAllAnnotationCommand implements PathCommand {
	
	private final static Logger logger = LoggerFactory.getLogger(SelectAllAnnotationCommand.class);
	
	final private ViewerManager<?>  qupath;
	
	public SelectAllAnnotationCommand(final ViewerManager<?> qupath) {
		super();
		this.qupath = qupath;
	}

	@Override
	public void run() {
		QuPathViewer viewer = qupath.getViewer();
		if (viewer == null)
			return;
		PathObjectHierarchy hierarchy = viewer.getHierarchy();
		if (hierarchy == null)
			return;
		// Check if we already have a comparable annotation
		ImageRegion bounds = viewer.getServerBounds();
		for (PathObject pathObject : hierarchy.getObjects(null, PathAnnotationObject.class)) {
			ROI roi = pathObject.getROI();
			if (roi instanceof RectangleROI && roi.getBoundsX() == bounds.getX() && roi.getBoundsY() == bounds.getY() && roi.getBoundsWidth() == bounds.getWidth() && roi.getBoundsHeight() == bounds.getHeight()) {
				logger.info("Select all annotation already exists! {}", pathObject);
				viewer.setSelectedObject(pathObject);
				return;
			}
		}
		RectangleROI roi = new RectangleROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
		viewer.createAnnotationObject(roi);
	}
	
}