/*
 * Copyright 2016, Stuart Douglas, and individual contributors as indicated
 * by the @authors tag.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.fakereplace.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;

public class AgentRunner extends FakereplaceWebsocketProtocol {

    private static final String REMOTE_PASSWORD = "remote.password";

    private final Map<String, Long> classChangeTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> resourceChangeTimes = new ConcurrentHashMap<>();
    //we only care about classes changed after the agent started
    private final long agentStart;

    private final String web;
    private final String srcs;
    private final String classes;
    private final String uri;
    private final String password;

    public AgentRunner(String web, String srcs, String classes, String uri, String password) {
        this.web = web;
        this.srcs = srcs;
        this.classes = classes;
        this.uri = uri;
        this.password = password;
        this.agentStart = System.currentTimeMillis();
    }

    public void run() {

        try {
            ContainerProvider.getWebSocketContainer().connectToServer(this, ClientEndpointConfig.Builder.create().configurator(new ClientEndpointConfig.Configurator() {
                @Override
                public void beforeRequest(Map<String, List<String>> headers) {
                    headers.put(REMOTE_PASSWORD, Collections.singletonList(password));
                }
            }).build(), new URI(uri));
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    protected Map<String, byte[]> changedClasses() {
        Map<String, byte[]> found = new HashMap<>();
        if (classes != null) {
            try {
                scanForClassChanges("", new File(classes), found);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
        return found;

    }

    protected Map<String, byte[]> changedSrcs() {
        Map<String, byte[]> found = new HashMap<>();
        if (srcs != null) {
            try {
                scanForClassChanges("", new File(srcs), found);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
        return found;
    }

    protected Map<String, byte[]> changedWebResources() {
        Map<String, byte[]> found = new HashMap<>();
        if (web != null) {
            try {
                scanForWebResources("", new File(web), found);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
        return found;
    }

    @Override
    protected void logMessage(String message) {
        System.out.println(message);
    }

    @Override
    protected void error(Throwable t) {
        t.printStackTrace();
        System.exit(1);
    }

    @Override
    protected void done() {
        System.exit(0);
    }

    private void scanForWebResources(String currentPath, File root, Map<String, byte[]> found) throws IOException {
        if (currentPath.equals("WEB-INF")) {
            return;
        }
        File serverCurrent = new File(root, currentPath);
        for (String part : serverCurrent.list()) {
            String fullPart = (currentPath.isEmpty() ? "" : (currentPath + File.separatorChar)) + part;
            File f = new File(serverCurrent, part);
            if (f.isDirectory()) {
                scanForWebResources(fullPart, root, found);
            } else {
                File localFile = new File(serverCurrent, part);
                Long recordedChange = resourceChangeTimes.get(fullPart);
                long lastModified = localFile.lastModified();
                if (recordedChange == null) {
                    recordedChange = agentStart;
                }
                if (recordedChange < lastModified) {
                    found.put(fullPart, readFile(localFile));
                    resourceChangeTimes.put(fullPart, lastModified);
                }
            }
        }
    }


    private void scanForClassChanges(String currentPath, File root, Map<String, byte[]> found) throws IOException {
        File serverCurrent = new File(root, currentPath);
        for (String part : serverCurrent.list()) {
            String fullPart = (currentPath.isEmpty() ? "" : (currentPath + File.separatorChar)) + part;
            File f = new File(serverCurrent, part);
            if (f.isDirectory()) {
                scanForClassChanges(fullPart, root, found);
            } else if (part.contains(".")) {
                File localFile = new File(serverCurrent, part);
                String fullPartAsClassFile = fullPart.substring(0, fullPart.lastIndexOf('.')) + ".class";

                Long recordedChange = classChangeTimes.get(fullPartAsClassFile);
                long lastModified = localFile.lastModified();
                if (recordedChange == null) {
                    recordedChange = agentStart;
                }
                if (recordedChange < lastModified) {
                    found.put(fullPart, readFile(localFile));
                    classChangeTimes.put(fullPartAsClassFile, lastModified);
                }
            }
        }
    }

    private byte[] readFile(File localFile) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(localFile)) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
        return out.toByteArray();
    }
}
