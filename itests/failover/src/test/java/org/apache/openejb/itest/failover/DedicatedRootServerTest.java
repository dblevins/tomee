/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.itest.failover;

import org.apache.openejb.client.RemoteInitialContextFactory;
import org.apache.openejb.itest.failover.ejb.Calculator;
import org.apache.openejb.loader.Files;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.Zips;
import org.apache.openejb.server.control.StandaloneServer;
import org.apache.openejb.util.NetworkUtil;
import org.junit.Assert;
import org.junit.Test;

import javax.ejb.EJBException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * In some situations it can be desirable to have
 * one dedicated multipoint root server which does
 * no other function other than to serve as a central
 * hub for making multipoint introductions.
 *
 * This dedicate root server will not serve applications
 * and not be added to the list of servers that can
 * service EJB requests.
 *
 */
public class DedicatedRootServerTest {

    @Test
    public void test() throws Exception {

        // To run in an IDE, uncomment and update this line
        //System.setProperty("version", "4.0.0-beta-3-SNAPSHOT");

        final Repository repository = new Repository();
        final File zip = repository.getArtifact("org.apache.openejb", "openejb-standalone", "zip").get();
        final File app = repository.getArtifact("org.apache.openejb.itests", "failover-ejb", "jar").get();

        final File dir = Files.tmpdir();

        final StandaloneServer root;
        {
            StandaloneServer root1;
            final String name = "root";
            final File home = new File(dir, name);

            Files.mkdir(home);
            Zips.unzip(zip, home, true);

            root1 = new StandaloneServer(home, home);
            root1.killOnExit();
            root1.ignoreOut();
            root1.setProperty("name", name);
            root1.setProperty("openejb.extract.configuration", "false");

            final StandaloneServer.ServerService multipoint = root1.getServerService("multipoint");
            multipoint.setBind("localhost");
            multipoint.setPort(getAvailablePort());
            multipoint.setDisabled(false);
            multipoint.set("discoveryName", name);
            root = root1;

            root.start();
        }


        final Map<String, StandaloneServer> servers = new HashMap<String, StandaloneServer>();
        for (String name : new String[]{"red", "green", "blue"}) {
            final File home = new File(dir, name);
            Files.mkdir(home);
            Zips.unzip(zip, home, true);

            final StandaloneServer server = new StandaloneServer(home, home);
            server.killOnExit();
            server.ignoreOut();
            server.setProperty("name", name);
            server.setProperty("openejb.extract.configuration", "false");

            IO.copy(app, Files.path(home, "apps", "itest.jar"));
            IO.copy(IO.read("<openejb><Deployments dir=\"apps/\"/></openejb>"), Files.path(home, "conf", "openejb.xml"));

            final StandaloneServer.ServerService ejbd = server.getServerService("ejbd");
            ejbd.setDisabled(false);
            ejbd.setPort(getAvailablePort());
            ejbd.setThreads(5);

            final StandaloneServer.ServerService multipoint = server.getServerService("multipoint");
            multipoint.setPort(getAvailablePort());
            multipoint.setDisabled(false);
            multipoint.set("discoveryName", name);
            multipoint.set("initialServers", "localhost:"+root.getServerService("multipoint").getPort());

            servers.put(name, server);
            server.start(1, TimeUnit.MINUTES);

            invoke(name, server);
        }

        System.setProperty("openejb.client.requestretry", "true");

        final Properties environment = new Properties();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, RemoteInitialContextFactory.class.getName());
        environment.put(Context.PROVIDER_URL, "ejbd://localhost:" + servers.values().iterator().next().getServerService("ejbd").getPort());

        final InitialContext context = new InitialContext(environment);
        final Calculator bean = (Calculator) context.lookup("CalculatorBeanRemote");


        String previous = null;
        for (StandaloneServer ignored : servers.values()) {

            // What server are we talking to now?
            final String name = bean.name();

            // The root should not be serving apps
            assertFalse("root".equals(name));

            // Should not be the same server we were talking with previously (we killed that server)
            if (previous != null) assertFalse(name.equals(previous));
            previous = name;

            // Should be the same server for the next N calls
            invoke(bean, 1000, name);

            // Now let's kill that server
            servers.get(name).kill();
        }

        System.out.println("All servers destroyed");

        try {
            final String name = bean.name();
            Assert.fail("Server should be destroyed: " + name);
        } catch (EJBException e) {
            // good
        }

        // Let's start a server again and invocations should now succeed
        final Iterator<StandaloneServer> iterator = servers.values().iterator();
        iterator.next();
        iterator.next().start(1, TimeUnit.MINUTES);

        assertEquals(5, bean.sum(2, 3));
    }

    private void invoke(String name, StandaloneServer server) throws NamingException {
        final Properties environment = new Properties();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, RemoteInitialContextFactory.class.getName());
        environment.put(Context.PROVIDER_URL, "ejbd://localhost:" + server.getServerService("ejbd").getPort());

        final InitialContext context = new InitialContext(environment);
        final Calculator bean = (Calculator) context.lookup("CalculatorBeanRemote");
        assertEquals(name, bean.name());
    }

    private long invoke(Calculator bean, int max, String expectedName) {

        long total = 0;

        for (int i = 0; i < max; i++) {
            final long start = System.nanoTime();
            String name = bean.name();
            Assert.assertEquals(expectedName, name);
            total += System.nanoTime() - start;
        }

        return TimeUnit.NANOSECONDS.toMicros(total / max);
    }

    private long invoke(Calculator bean, int max) {

        long total = 0;

        for (int i = 0; i < max; i++) {
            final long start = System.nanoTime();
            Assert.assertEquals(3, bean.sum(1, 2));
            total += System.nanoTime() - start;
        }

        return TimeUnit.NANOSECONDS.toMicros(total / max);
    }

    private int getAvailablePort() {
        return NetworkUtil.getNextAvailablePort();
    }
}
