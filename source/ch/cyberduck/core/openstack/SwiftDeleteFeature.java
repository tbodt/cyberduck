package ch.cyberduck.core.openstack;

/*
 * Copyright (c) 2013 David Kocher. All rights reserved.
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * feedback@cyberduck.ch
 */

import ch.cyberduck.core.DefaultIOExceptionMappingService;
import ch.cyberduck.core.DisabledLoginCallback;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.LoginCallback;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathContainerService;
import ch.cyberduck.core.ProgressListener;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.NotfoundException;
import ch.cyberduck.core.features.Delete;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;

import ch.iterate.openstack.swift.exception.GenericException;

/**
 * @version $Id$
 */
public class SwiftDeleteFeature implements Delete {
    private static final Logger log = Logger.getLogger(SwiftDeleteFeature.class);

    private SwiftSession session;

    private PathContainerService containerService
            = new SwiftPathContainerService();

    private SwiftSegmentService segmentService;

    private SwiftRegionService regionService;

    public SwiftDeleteFeature(final SwiftSession session) {
        this(session, new SwiftSegmentService(session), new SwiftRegionService(session));
    }

    public SwiftDeleteFeature(final SwiftSession session, final SwiftSegmentService segmentService,
                              final SwiftRegionService regionService) {
        this.segmentService = segmentService;
        this.regionService = regionService;
        this.session = session;
    }

    @Override
    public void delete(final List<Path> files, final LoginCallback prompt, final ProgressListener listener) throws BackgroundException {
        for(Path file : files) {
            listener.message(MessageFormat.format(LocaleFactory.localizedString("Deleting {0}", "Status"),
                    file.getName()));
            try {
                if(file.isFile()) {
                    // Collect a list of existing segments. Must do this before deleting the manifest file.
                    final List<Path> segments = segmentService.list(file);
                    session.getClient().deleteObject(regionService.lookup(containerService.getContainer(file)),
                            containerService.getContainer(file).getName(), containerService.getKey(file));
                    if(!segments.isEmpty()) {
                        // Clean up any old segments
                        new SwiftMultipleDeleteFeature(session).delete(segments, new DisabledLoginCallback(), listener);
                    }
                }
                else if(file.isDirectory()) {
                    if(containerService.isContainer(file)) {
                        session.getClient().deleteContainer(regionService.lookup(containerService.getContainer(file)),
                                containerService.getContainer(file).getName());
                    }
                    else {
                        try {
                            session.getClient().deleteObject(regionService.lookup(containerService.getContainer(file)),
                                    containerService.getContainer(file).getName(), containerService.getKey(file));
                        }
                        catch(GenericException e) {
                            if(new SwiftExceptionMappingService().map(e) instanceof NotfoundException) {
                                log.warn(String.format("Ignore missing placeholder object %s", file));
                            }
                            else {
                                throw e;
                            }
                        }
                    }
                }
            }
            catch(GenericException e) {
                throw new SwiftExceptionMappingService().map("Cannot delete {0}", e, file);
            }
            catch(IOException e) {
                throw new DefaultIOExceptionMappingService().map("Cannot delete {0}", e, file);
            }
        }
    }
}
