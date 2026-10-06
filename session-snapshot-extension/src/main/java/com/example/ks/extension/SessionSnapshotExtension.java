package com.example.ks.extension;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.kie.api.KieServices;
import org.kie.api.marshalling.Marshaller;
import org.kie.api.runtime.CommandExecutor;
import org.kie.api.runtime.KieSession;
import org.kie.server.services.api.KieContainerInstance;
import org.kie.server.services.api.KieServerExtension;
import org.kie.server.services.api.KieServerRegistry;
import org.kie.server.services.api.SupportedTransports;
import org.kie.server.services.impl.KieServerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KIE Server extension that automatically saves and restores stateful KIE sessions
 * across container deployments and server restarts via file snapshotting.
 */
public class SessionSnapshotExtension implements KieServerExtension {

    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotExtension.class);
    public static final String EXTENSION_NAME = "SessionSnapshot";
    public static final String SNAPSHOT_DIR_PROP = "kie.session.snapshot.dir";

    private String snapshotDir;
    private KieServerRegistry registry;
    private boolean initialized;

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public Integer getStartOrder() {
        return 20;
    }

    @Override
    public String getExtensionName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getImplementedCapability() {
        return "BRM-SNAPSHOT";
    }

    @Override
    public List<Object> getServices() {
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return EXTENSION_NAME + " KIE Server extension";
    }

    @Override
    public void init(KieServerImpl kieServer, KieServerRegistry registry) {
        this.registry = registry;
        snapshotDir = System.getProperty(SNAPSHOT_DIR_PROP);
        if (snapshotDir == null || snapshotDir.isBlank()) {
            log.warn("{} extension: '{}' system property is not set. " +
                     "Set -D{}=/your/path before starting the server. " +
                     "Session save/restore will be DISABLED until the property is present.",
                    EXTENSION_NAME, SNAPSHOT_DIR_PROP, SNAPSHOT_DIR_PROP);
            snapshotDir = null;
        }

        initialized = true;
        log.info("{} extension initialized. Snapshot directory: {}",
                EXTENSION_NAME, snapshotDir != null ? snapshotDir : "<NOT SET — save/restore disabled>");
    }

    @Override
    public void destroy(KieServerImpl kieServer, KieServerRegistry registry) {
        // Individual containers are saved in disposeContainer
    }

    @Override
    public void createContainer(String id, KieContainerInstance container, Map<String, Object> parameters) {
        if (snapshotDir == null) {
            log.warn("[createContainer] Skipping restore for container={} — '{}' not configured",
                    id, SNAPSHOT_DIR_PROP);
            return;
        }
        forEachSession(id, container, (sessionId, session) -> {
            File snapshot = snapshotFile(id, sessionId);
            if (!snapshot.exists()) {
                log.info("[createContainer] No snapshot for container={} session={} (checked {}) — starting empty",
                        id, sessionId, snapshot.getAbsolutePath());
                return;
            }
            restoreSession(id, sessionId, session, snapshot);
        });
    }

    @Override
    public void disposeContainer(String id, KieContainerInstance container, Map<String, Object> parameters) {
        log.info("[disposeContainer] Auto-saving all sessions for container={}", id);
        forEachSession(id, container, (sessionId, session) ->
                saveSession(id, sessionId, session, "disposeContainer"));
    }

    @Override
    public void updateContainer(String id, KieContainerInstance container, Map<String, Object> parameters) {
    }

    @Override
    public boolean isUpdateContainerAllowed(String id, KieContainerInstance container, Map<String, Object> parameters) {
        return true;
    }

    @Override
    public List<Object> getAppComponents(SupportedTransports type) {
        return Collections.emptyList();
    }

    @Override
    public <T> T getAppComponents(Class<T> serviceType) {
        return null;
    }

    public void forEachSession(String containerId, KieContainerInstance container, SessionAction action) {
        Collection<String> kbaseNames = container.getKieContainer().getKieBaseNames();
        if (kbaseNames.isEmpty()) {
            log.info("No KBases found in container {}", containerId);
            return;
        }

        for (String kbaseName : kbaseNames) {
            Collection<String> sessionNames = container.getKieContainer().getKieSessionNamesInKieBase(kbaseName);

            for (String sessionName : sessionNames) {
                CommandExecutor executor = registry.getKieSessionLookupManager()
                        .lookup(sessionName, container, registry);

                if (!(executor instanceof KieSession)) {
                    log.warn("container={} session={} — lookup did not return a KieSession, skipping",
                            containerId, sessionName);
                    continue;
                }

                try {
                    action.accept(sessionName, (KieSession) executor);
                } catch (Exception e) {
                    log.error("container={} session={} — action failed", containerId, sessionName, e);
                }
            }
        }
    }

    public boolean saveSession(String containerId, String sessionName, KieSession session, String caller) {
        if (snapshotDir == null) {
            log.warn("[{}] Skipping save for container={} session={} — '{}' not configured",
                    caller, containerId, sessionName, SNAPSHOT_DIR_PROP);
            return false;
        }
        File target = snapshotFile(containerId, sessionName);
        File tmp = new File(target.getParent(), target.getName() + ".tmp");
        target.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            Marshaller marshaller = KieServices.get().getMarshallers().newMarshaller(session.getKieBase());
            marshaller.marshall(fos, session);
        } catch (IOException e) {
            log.error("[{}] Save failed for container={} session={}", caller, containerId, sessionName, e);
            tmp.delete();
            return false;
        }

        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[{}] Atomic rename failed: {} → {}", caller, tmp, target, e);
            return false;
        }

        log.info("[{}] Saved container={} session={} — {} facts → {}",
                caller, containerId, sessionName, session.getFactCount(), target);
        return true;
    }

    private void restoreSession(String containerId, String sessionName, KieSession session, File snapshot) {
        try (FileInputStream fis = new FileInputStream(snapshot)) {
            Marshaller marshaller = KieServices.get().getMarshallers().newMarshaller(session.getKieBase());
            marshaller.unmarshall(fis, session);
            log.info("[createContainer] Restored container={} session={} — {} facts from {}",
                    containerId, sessionName, session.getFactCount(), snapshot);
        } catch (Exception e) {
            log.error("[createContainer] Restore failed for container={} session={}",
                    containerId, sessionName, e);
        }
    }

    public File snapshotFile(String containerId, String sessionName) {
        String dir = snapshotDir != null ? snapshotDir : ".";
        return new File(dir, containerId + "-" + sessionName + ".ser");
    }

    public String getSnapshotDir() {
        return snapshotDir;
    }

    @FunctionalInterface
    public interface SessionAction {
        void accept(String sessionName, KieSession session) throws Exception;
    }
}