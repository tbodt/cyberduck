package ch.cyberduck.core.openstack;

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

import ch.cyberduck.core.*;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import ch.iterate.openstack.swift.model.AccountInfo;
import ch.iterate.openstack.swift.model.Region;

import static org.junit.Assert.*;

/**
 * @version $Id$
 */
public class SwiftUrlProviderTest extends AbstractTestCase {

    @Test
    public void testGet() throws Exception {
        final SwiftSession session = new SwiftSession(new Host(new SwiftProtocol(), "identity.api.rackspacecloud.com",
                new Credentials(properties.getProperty("rackspace.key"), properties.getProperty("rackspace.secret"))
        ));
        session.open(new DefaultHostKeyController());
        session.login(new DisabledPasswordStore(), new DisabledLoginController());
        final Path container = new Path("test.cyberduck.ch", Path.VOLUME_TYPE);
        container.attributes().setRegion("DFW");
        assertEquals("https://storage101.dfw1.clouddrive.com/v1/MossoCloudFS_59113590-c679-46c3-bf62-9d7c3d5176ee/test.cyberduck.ch/f",
                new SwiftUrlProvider(session).toUrl(new Path(container, "f", Path.FILE_TYPE)).find(DescriptiveUrl.Type.provider).getUrl());
        session.close();
    }

    @Test
    public void testSignedHp() throws Exception {
        final SwiftProtocol protocol = new SwiftProtocol() {
            @Override
            public String getContext() {
                return "/v2.0/tokens";
            }
        };
        final Host host = new Host(protocol, "region-a.geo-1.identity.hpcloudsvc.com", 35357, new Credentials(
                properties.getProperty("hpcloud.key"), properties.getProperty("hpcloud.secret")
        ));
        final SwiftSession session = new SwiftSession(host);
        session.open(new DefaultHostKeyController());
        session.login(new DisabledPasswordStore(), new DisabledLoginController());
        final Path container = new Path("test.cyberduck.ch", Path.VOLUME_TYPE);
        final Path file = new Path(container, UUID.randomUUID().toString(), Path.FILE_TYPE);
        new SwiftTouchFeature(session).touch(file);
        final UrlProvider provider = new SwiftUrlProvider(session, session.accounts, new DisabledPasswordStore() {
            @Override
            public String getPassword(final Scheme scheme, final int port, final String hostname, final String user) {
                return properties.getProperty("hpcloud.secret");
            }
        });
        assertTrue(provider.toUrl(file).find(DescriptiveUrl.Type.signed).getUrl().startsWith(
                "https://region-a.geo-1.objects.hpcloudsvc.com/v1/88650632417788/test.cyberduck.ch/" + file.getName()));
        new SwiftDeleteFeature(session).delete(Collections.singletonList(file), new DisabledLoginController());
        session.close();
    }

    @Test
    public void testSignedRax() throws Exception {
        final SwiftSession session = new SwiftSession(new Host(new SwiftProtocol(), "identity.api.rackspacecloud.com",
                new Credentials(properties.getProperty("rackspace.key"), properties.getProperty("rackspace.secret"))
        ));
        final UrlProvider provider = new SwiftUrlProvider(session, session.accounts, new DisabledPasswordStore());
        final Path container = new Path("test.cyberduck.ch", Path.VOLUME_TYPE);
        final Path file = new Path(container, UUID.randomUUID().toString(), Path.FILE_TYPE);
        assertEquals(DescriptiveUrl.EMPTY, provider.toUrl(file).find(DescriptiveUrl.Type.signed));
        session.open(new DefaultHostKeyController());
        session.login(new DisabledPasswordStore(), new DisabledLoginController());
        for(String region : new SwiftLocationFeature(session).getLocations()) {
            container.attributes().setRegion(region);
            new SwiftTouchFeature(session).touch(file);
            final DescriptiveUrlBag list = provider.toUrl(file);
            assertNotNull(list.find(DescriptiveUrl.Type.signed));
            if(session.accounts.containsKey(new SwiftRegionService(session).lookup(container))) {
                if(session.accounts.get(new SwiftRegionService(session).lookup(container)).getTempUrlKey() != null) {
                    assertNotEquals(DescriptiveUrl.EMPTY, list.find(DescriptiveUrl.Type.signed));
                }
            }
            new SwiftDeleteFeature(session).delete(Collections.singletonList(file), new DisabledLoginController());
        }
        session.close();
    }

    @Test
    public void testTempUrl() throws Exception {
        final SwiftSession session = new SwiftSession(new Host(new SwiftProtocol(), "identity.api.rackspacecloud.com",
                new Credentials(properties.getProperty("rackspace.key"), properties.getProperty("rackspace.secret"))
        ));
        final Region region = new Region("DFW", URI.create("http://storage101.hkg1.clouddrive.com/v1/MossoCloudFS_59113590-c679-46c3-bf62-9d7c3d5176ee"), URI.create("http://m"));
        Map accounts = new HashMap();
        accounts.put(region, new AccountInfo(1L, 1, "k"));
        final Path container = new Path("test w.cyberduck.ch", Path.VOLUME_TYPE);
        final Path file = new Path(container, "key", Path.FILE_TYPE);
        final SwiftUrlProvider provider = new SwiftUrlProvider(session, accounts);
        assertNotEquals(DescriptiveUrl.EMPTY, provider.createTempUrl(region, file, 300));
        assertTrue(provider.createTempUrl(region, file, 300).getUrl().startsWith(
                "https://storage101.hkg1.clouddrive.com/v1/MossoCloudFS_59113590-c679-46c3-bf62-9d7c3d5176ee/test%20w.cyberduck.ch/"));
    }
}
