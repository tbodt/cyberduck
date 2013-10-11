package ch.cyberduck.core.transfer.upload;

/*
 * Copyright (c) 2002-2013 David Kocher. All rights reserved.
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
 * Bug fixes, suggestions and comments should be sent to feedback@cyberduck.ch
 */

import ch.cyberduck.core.Acl;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.Permission;
import ch.cyberduck.core.Preferences;
import ch.cyberduck.core.ProgressListener;
import ch.cyberduck.core.Session;
import ch.cyberduck.core.UserDateFormatterFactory;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.NotfoundException;
import ch.cyberduck.core.features.AclPermission;
import ch.cyberduck.core.features.Attributes;
import ch.cyberduck.core.features.Find;
import ch.cyberduck.core.features.Move;
import ch.cyberduck.core.features.Timestamp;
import ch.cyberduck.core.features.UnixPermission;
import ch.cyberduck.core.transfer.TransferOptions;
import ch.cyberduck.core.transfer.TransferPathFilter;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.core.transfer.symlink.SymlinkResolver;

import org.apache.log4j.Logger;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @version $Id$
 */
public abstract class AbstractUploadFilter implements TransferPathFilter {
    private static final Logger log = Logger.getLogger(AbstractUploadFilter.class);

    private SymlinkResolver symlinkResolver;

    private Session<?> session;

    protected Map<Path, Path> temporary
            = new HashMap<Path, Path>();

    private UploadFilterOptions options;

    private Find find;

    private Attributes attribute;

    public AbstractUploadFilter(final SymlinkResolver symlinkResolver, final Session<?> session, final UploadFilterOptions options) {
        this.symlinkResolver = symlinkResolver;
        this.session = session;
        this.options = options;
        this.find = session.getFeature(Find.class);
        this.attribute = session.getFeature(Attributes.class);
    }

    @Override
    public boolean accept(final Path file, final TransferStatus parent) throws BackgroundException {
        if(!file.getLocal().exists()) {
            // Local file is no more here
            throw new NotfoundException(file.getLocal().getAbsolute());
        }
        if(file.attributes().isFile()) {
            if(file.getLocal().attributes().isSymbolicLink()) {
                if(!symlinkResolver.resolve(file)) {
                    return symlinkResolver.include(file);
                }
            }
        }
        return true;
    }

    @Override
    public TransferStatus prepare(final Path file, final TransferStatus parent) throws BackgroundException {
        final TransferStatus status = new TransferStatus();
        if(file.attributes().isFile()) {
            if(file.getLocal().attributes().isSymbolicLink()) {
                if(symlinkResolver.resolve(file)) {
                    // No file size increase for symbolic link to be created on the server
                }
                else {
                    // Will resolve the symbolic link when the file is requested.
                    final Local target = file.getLocal().getSymlinkTarget();
                    status.setLength(target.attributes().getSize());
                }
            }
            else {
                // Read file size from filesystem
                status.setLength(file.getLocal().attributes().getSize());
            }
            if(options.temporary) {
                final Path renamed = new Path(file.getParent(), MessageFormat.format(Preferences.instance().getProperty("queue.upload.file.temporary.format"),
                        file.getName(), UUID.randomUUID().toString()), file.attributes(), file.getLocal());
                status.setRenamed(renamed);
                temporary.put(file, renamed);
            }
        }
        if(parent.isExists()) {
            // Do not attempt to create a directory that already exists
            if(find.find(file)) {
                status.setExists(true);
                file.setAttributes(attribute.getAttributes(file));
            }
        }
        return status;
    }

    @Override
    public void apply(final Path file, final TransferStatus parent) throws BackgroundException {
        //
    }

    @Override
    public void complete(final Path file, final TransferOptions options,
                         final TransferStatus status, final ProgressListener listener) throws BackgroundException {
        if(log.isDebugEnabled()) {
            log.debug(String.format("Complete %s with status %s", file.getAbsolute(), status));
        }
        if(status.isComplete()) {
            if(this.options.permissions) {
                final UnixPermission unix = session.getFeature(UnixPermission.class);
                if(unix != null) {
                    listener.message(MessageFormat.format(LocaleFactory.localizedString("Changing permission of {0} to {1}", "Status"),
                            file.getName(), file.attributes().getPermission().getMode()));
                    this.permissions(file, unix);
                }
                final AclPermission acl = session.getFeature(AclPermission.class);
                if(acl != null) {
                    listener.message(MessageFormat.format(LocaleFactory.localizedString("Changing permission of {0} to {1}", "Status"),
                            file.getName(), file.attributes().getPermission().getMode()));
                    this.acl(file, acl);
                }
            }
            if(this.options.timestamp) {
                final Timestamp timestamp = session.getFeature(Timestamp.class);
                if(timestamp != null) {
                    listener.message(MessageFormat.format(LocaleFactory.localizedString("Changing timestamp of {0} to {1}", "Status"),
                            file.getName(), UserDateFormatterFactory.get().getShortFormat(file.getLocal().attributes().getModificationDate())));
                    this.timestamp(file, timestamp);

                }
            }
            if(file.attributes().isFile()) {
                if(this.options.temporary) {
                    final Move move = session.getFeature(Move.class);
                    move.move(temporary.get(file), file);
                    temporary.remove(file);
                }
            }
        }
    }

    private void timestamp(final Path file, final Timestamp feature) {
        // Read timestamps from local file
        try {
            feature.setTimestamp(file, file.getLocal().attributes().getCreationDate(),
                    file.getLocal().attributes().getModificationDate(),
                    file.getLocal().attributes().getAccessedDate());
        }
        catch(BackgroundException e) {
            // Ignore
            log.warn(e.getMessage());
        }
    }

    private void permissions(final Path file, final UnixPermission feature) {
        final Permission permission;
        if(Preferences.instance().getBoolean("queue.upload.permissions.useDefault")) {
            if(file.attributes().isFile()) {
                permission = new Permission(
                        Preferences.instance().getInteger("queue.upload.permissions.file.default"));
            }
            else {
                permission = new Permission(
                        Preferences.instance().getInteger("queue.upload.permissions.folder.default"));
            }
        }
        else {
            // Read permissions from local file
            permission = file.getLocal().attributes().getPermission();
        }
        if(!Permission.EMPTY.equals(permission)) {
            try {
                feature.setUnixPermission(file, permission);
            }
            catch(BackgroundException e) {
                // Ignore
                log.warn(e.getMessage());
            }
        }
    }

    private void acl(final Path file, final AclPermission feature) {
        final Permission permission;
        if(Preferences.instance().getBoolean("queue.upload.permissions.useDefault")) {
            if(file.attributes().isFile()) {
                permission = new Permission(
                        Preferences.instance().getInteger("queue.upload.permissions.file.default"));
            }
            else {
                permission = new Permission(
                        Preferences.instance().getInteger("queue.upload.permissions.folder.default"));
            }
        }
        else {
            // Read permissions from local file
            permission = file.getLocal().attributes().getPermission();
        }
        final Acl acl = new Acl();
        if(permission.getOther().implies(Permission.Action.read)) {
            acl.addAll(new Acl.GroupUser(Acl.GroupUser.EVERYONE), new Acl.Role(Acl.Role.READ));
        }
        if(permission.getGroup().implies(Permission.Action.read)) {
            acl.addAll(new Acl.GroupUser(Acl.GroupUser.AUTHENTICATED), new Acl.Role(Acl.Role.READ));
        }
        if(permission.getGroup().implies(Permission.Action.write)) {
            acl.addAll(new Acl.GroupUser(Acl.GroupUser.AUTHENTICATED), new Acl.Role(Acl.Role.WRITE));
        }
        if(!Acl.EMPTY.equals(acl)) {
            try {
                feature.setPermission(file, acl);
            }
            catch(BackgroundException e) {
                // Ignore
                log.warn(e.getMessage());
            }
        }
    }
}