package com.example.ks.extension;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.kie.api.runtime.CommandExecutor;
import org.kie.api.runtime.KieSession;
import org.kie.server.services.api.KieContainerInstance;
import org.kie.server.services.api.KieServerApplicationComponentsService;
import org.kie.server.services.api.KieServerRegistry;
import org.kie.server.services.api.SupportedTransports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers REST endpoint:
 *   POST /server/containers/{containerId}/sessions/{sessionId}/snapshot
 *   GET  /server/containers/{containerId}/sessions/{sessionId}/snapshot
 */
public class SessionSnapshotAppComponents implements KieServerApplicationComponentsService {

    private static final String OWNER_EXTENSION = "Drools";

    @Override
    public Collection<Object> getAppComponents(String extension, SupportedTransports type, Object... services) {
        if (!OWNER_EXTENSION.equals(extension)) {
            return Collections.emptyList();
        }

        KieServerRegistry registry = null;
        for (Object svc : services) {
            if (svc instanceof KieServerRegistry) {
                registry = (KieServerRegistry) svc;
                break;
            }
        }

        if (registry == null || !SupportedTransports.REST.equals(type)) {
            return Collections.emptyList();
        }

        List<Object> components = new ArrayList<>();
        components.add(new SessionSnapshotResource(registry));
        return components;
    }

    @Path("server/containers/{containerId}/sessions/{sessionId}/snapshot")
    public static class SessionSnapshotResource {

        private static final Logger log = LoggerFactory.getLogger(SessionSnapshotResource.class);
        private final KieServerRegistry registry;

        public SessionSnapshotResource(KieServerRegistry registry) {
            this.registry = registry;
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        public Response takeSnapshot(@PathParam("containerId") String containerId,
                                     @PathParam("sessionId") String sessionId) {
            KieContainerInstance container = registry.getContainer(containerId);
            if (container == null) {
                return Response.status(404)
                        .entity("{\"status\":\"ERROR\",\"message\":\"No such container: " + containerId + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            SessionSnapshotExtension ext = getExtension();
            if (ext == null) {
                return Response.status(503)
                        .entity("{\"status\":\"ERROR\",\"message\":\"SessionSnapshotExtension not active\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            if (ext.getSnapshotDir() == null) {
                return Response.status(500)
                        .entity("{\"status\":\"ERROR\",\"message\":\"kie.session.snapshot.dir is not configured\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            CommandExecutor executor = registry.getKieSessionLookupManager().lookup(sessionId, container, registry);
            if (!(executor instanceof KieSession)) {
                return Response.status(404)
                        .entity("{\"status\":\"ERROR\",\"message\":\"Session not found: " + sessionId + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            KieSession session = (KieSession) executor;
            boolean saved = ext.saveSession(containerId, sessionId, session, "REST-POST-snapshot");
            if (!saved) {
                return Response.status(500)
                        .entity("{\"status\":\"ERROR\",\"message\":\"Failed to save snapshot\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            File file = ext.snapshotFile(containerId, sessionId);
            long factCount = session.getFactCount();
            String json = String.format("{\"status\":\"SUCCESS\",\"containerId\":\"%s\",\"sessionId\":\"%s\",\"factCount\":%d,\"snapshotPath\":\"%s\",\"size\":%d}",
                    containerId, sessionId, factCount, file.getAbsolutePath().replace("\\", "\\\\"), file.length());
            log.info("[REST] Created snapshot: {}", json);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Response getSnapshotInfo(@PathParam("containerId") String containerId,
                                        @PathParam("sessionId") String sessionId) {
            SessionSnapshotExtension ext = getExtension();
            if (ext == null) {
                return Response.status(503)
                        .entity("{\"status\":\"ERROR\",\"message\":\"SessionSnapshotExtension not active\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            File file = ext.snapshotFile(containerId, sessionId);
            boolean exists = file.exists() && file.length() > 0;
            String json = String.format("{\"status\":\"SUCCESS\",\"containerId\":\"%s\",\"sessionId\":\"%s\",\"exists\":%b,\"snapshotPath\":\"%s\",\"size\":%d}",
                    containerId, sessionId, exists, file.getAbsolutePath().replace("\\", "\\\\"), exists ? file.length() : 0);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
        }

        private SessionSnapshotExtension getExtension() {
            org.kie.server.services.api.KieServerExtension ext =
                    registry.getServerExtension(SessionSnapshotExtension.EXTENSION_NAME);
            return (ext instanceof SessionSnapshotExtension) ? (SessionSnapshotExtension) ext : null;
        }
    }
}