package com.example.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves stateful KIE session migration from BAMOE 8.0 on EAP 7.4 to BAMOE 8.1 on EAP 8.1.
 *
 * Sequence of Events:
 * 1. Build and install loan-kjar & session-snapshot-extension
 * 2. Start BAMOE 8.0 server (local EAP 7.4) with extension injected and snapshot directory configured
 * 3. Deploy loan-kjar to 8.0
 * 4. Insert Applicants 1, 2, 3 into KBaseKS_stateful on 8.0 -> Assert 1 firing each and total 6 facts
 * 5. Take session snapshot on 8.0 -> Assert .ser file exists in host staging dir and size > 0
 * 6. Stop 8.0 server
 * 7. Transfer / verify .ser snapshot for 8.1
 * 8. Start BAMOE 8.1 server (local EAP 8.1) with extension injected and snapshot directory configured
 * 9. Wait for 8.1 readiness and verify snapshot auto-restored
 * 10. Insert Applicant 4 on 8.1 -> Assert EXACTLY 1 firing (proving 3 prior applicants were restored and not re-fired)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StatefulSessionMigrationIT {

    private static final Logger log = LoggerFactory.getLogger(StatefulSessionMigrationIT.class);

    private static final String CONTAINER_ID = System.getProperty("kie.container.id", "loan-container");
    private static final String SESSION_ID = System.getProperty("kie.session.id", "KBaseKS_stateful");
    private static final String GROUP_ID = "com.example";
    private static final String ARTIFACT_ID = "loan-kjar";
    private static final String VERSION = "1.0.0";

    private static final String KIE_USER = System.getProperty("kieserver.user", "adminUser");
    private static final String KIE_PASS = System.getProperty("kieserver.password", "admin@Redhat1");

    private Path snapshotHostDir;
    private Path snapshot80Dir;
    private Path snapshot81Dir;

    private Path kjarJarPath;
    private Path extensionJarPath;

    // Local Server Harnesses
    private LocalServerHarness eap80LocalServer;
    private LocalServerHarness eap81LocalServer;

    private KieServerClient client80;
    private KieServerClient client81;

    @BeforeAll
    void buildAndPrepare() throws Exception {
        log.info("================================================================================");
        log.info("Starting Stateful KIE Session Migration IT (BAMOE 8.0 -> BAMOE 8.1)");
        log.info("================================================================================");

        // 1. Resolve build artifacts
        String kjarProp = System.getProperty("kjar.jar.path", "../loan-kjar/target/loan-kjar-1.0.0.jar");
        String extProp = System.getProperty("extension.jar.path", "../session-snapshot-extension/target/session-snapshot-extension-1.0.0.jar");

        kjarJarPath = Paths.get(kjarProp).toAbsolutePath().normalize();
        extensionJarPath = Paths.get(extProp).toAbsolutePath().normalize();

        // 2. Prepare staging directories on host — always start clean so a leftover snapshot
        //    from a previous run cannot be restored by EAP 8.0 on startup and corrupt the counts.
        String stagingDirProp = System.getProperty("snapshot.dir", "target/snapshot-staging");
        snapshotHostDir = Paths.get(stagingDirProp).toAbsolutePath().normalize();
        snapshot80Dir = snapshotHostDir.resolve("eap80");
        snapshot81Dir = snapshotHostDir.resolve("eap81");

        cleanDir(snapshot80Dir);
        cleanDir(snapshot81Dir);

        log.info("Host Snapshot 8.0 Dir: {}", snapshot80Dir);
        log.info("Host Snapshot 8.1 Dir: {}", snapshot81Dir);

        // 3. Resolve local EAP installation paths.
        // user.dir points to the migration-it module directory when running from Maven.
        // EAP installations are at the workspace root (two levels up from migration-it).
        String userDir = System.getProperty("user.dir");
        Path workspaceRoot = Paths.get(userDir).toAbsolutePath().normalize().getParent().getParent();

        Path eap80Default = workspaceRoot.resolve("jboss-eap-7.4").toAbsolutePath().normalize();
        Path eap81Default = workspaceRoot.resolve("jboss-eap-8.1").toAbsolutePath().normalize();

        String eap80HomeProp = System.getProperty("eap80.home", eap80Default.toString());
        String eap81HomeProp = System.getProperty("eap81.home", eap81Default.toString());

        Path eap80Path = Paths.get(eap80HomeProp).toAbsolutePath().normalize();
        Path eap81Path = Paths.get(eap81HomeProp).toAbsolutePath().normalize();

        if (!Files.isDirectory(eap80Path) || !Files.isDirectory(eap81Path)) {
            throw new IllegalStateException("Local EAP installations not found.\n"
                    + "Checked EAP 8.0: " + eap80Path + "\n"
                    + "Checked EAP 8.1: " + eap81Path + "\n"
                    + "Workspace root: " + workspaceRoot);
        }

        log.info("Using Local EAP mode:\n  EAP 8.0: {}\n  EAP 8.1: {}", eap80Path, eap81Path);

        // 4. Start BAMOE 8.0
        start80Local(eap80Path);
        log.info("BAMOE 8.0 is UP at {}", client80.getBaseUrl());

        // 5. Deploy container on BAMOE 8.0
        client80.deployContainer(CONTAINER_ID, GROUP_ID, ARTIFACT_ID, VERSION);
        log.info("Container {} deployed on BAMOE 8.0", CONTAINER_ID);
    }

    private void start80Local(Path eap80Path) throws Exception {
        Path workDir = snapshotHostDir.resolve("work-eap80");
        Files.createDirectories(workDir);
        String eap80JavaHome = System.getProperty("eap80.java.home", System.getenv("JAVA_HOME_11") != null ? System.getenv("JAVA_HOME_11") : "");
        eap80LocalServer = new LocalServerHarness("eap80", eap80Path, 0, eap80JavaHome,
                snapshot80Dir, workDir, "/kie-server/services/rest/server", KIE_USER, KIE_PASS);

        eap80LocalServer.injectExtension(extensionJarPath);
        eap80LocalServer.installKjar(kjarJarPath);
        eap80LocalServer.start();
        eap80LocalServer.waitUntilReady(Duration.ofMinutes(4));
        client80 = eap80LocalServer.getClient();
    }

    @Test
    @Order(1)
    void applicant1CausesOneFiring() {
        log.info("Test 1: Inserting Applicant 1 (Age 20) on BAMOE 8.0");
        int fired = client80.insertApplicantAndFire(CONTAINER_ID, SESSION_ID, "1", 20);
        assertThat(fired)
                .as("Applicant 1 (age 20) should trigger exactly 1 rule firing (Underage rule)")
                .isEqualTo(1);
    }

    @Test
    @Order(2)
    void applicant2CausesOneFiring() {
        log.info("Test 2: Inserting Applicant 2 (Age 21) on BAMOE 8.0");
        int fired = client80.insertApplicantAndFire(CONTAINER_ID, SESSION_ID, "2", 21);
        assertThat(fired)
                .as("Applicant 2 (age 21) should trigger exactly 1 rule firing (Approved rule)")
                .isEqualTo(1);
    }

    @Test
    @Order(3)
    void applicant3CausesOneFiring() {
        log.info("Test 3: Inserting Applicant 3 (Age 35) on BAMOE 8.0");
        int fired = client80.insertApplicantAndFire(CONTAINER_ID, SESSION_ID, "3", 35);
        assertThat(fired)
                .as("Applicant 3 (age 35) should trigger exactly 1 rule firing (Approved rule)")
                .isEqualTo(1);
    }

    @Test
    @Order(4)
    void snapshotContainsSixFacts() {
        log.info("Test 4: Verifying working memory facts count on BAMOE 8.0");
        int factsCount = client80.getFactCount(CONTAINER_ID, SESSION_ID);
        assertThat(factsCount)
                .as("BAMOE 8.0 stateful session must contain exactly 6 facts (3 Applicants + 3 LoanApplications)")
                .isEqualTo(6);

        log.info("Taking snapshot of container {} session {} on BAMOE 8.0", CONTAINER_ID, SESSION_ID);
        client80.snapshotSession(CONTAINER_ID, SESSION_ID);
    }

    @Test
    @Order(5)
    void snapshotFileExistsAndIsNonEmpty() {
        log.info("Test 5: Validating serialized snapshot file on host");
        Path snapshotFile80 = snapshot80Dir.resolve(CONTAINER_ID + "-" + SESSION_ID + ".ser");
        assertThat(Files.exists(snapshotFile80))
                .as("Snapshot file %s must exist on host after snapshot REST call", snapshotFile80)
                .isTrue();

        try {
            long size = Files.size(snapshotFile80);
            assertThat(size)
                    .as("Snapshot file size must be > 0 bytes (was %d bytes)", size)
                    .isGreaterThan(0);
            log.info("Verified snapshot file {} with size {} bytes", snapshotFile80, size);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read snapshot file size", e);
        }
    }

    @Test
    @Order(6)
    void migrateTo81AndApplicant4CausesExactlyOneFiring() throws Exception {
        log.info("Test 6: Migrating session state to BAMOE 8.1 and validating rule execution");

        // 1. Stop 8.0 server
        log.info("Stopping BAMOE 8.0 server...");
        if (eap80LocalServer != null) {
            eap80LocalServer.stopGracefully();
        }

        // 2. Transfer snapshot from 8.0 staging to 8.1 staging
        Path snapshotFile80 = snapshot80Dir.resolve(CONTAINER_ID + "-" + SESSION_ID + ".ser");
        Path snapshotFile81 = snapshot81Dir.resolve(CONTAINER_ID + "-" + SESSION_ID + ".ser");
        Files.copy(snapshotFile80, snapshotFile81, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied snapshot from {} to {}", snapshotFile80, snapshotFile81);

        // 3. Start BAMOE 8.1 server
        String eap81HomeProp = System.getProperty("eap81.home", System.getProperty("user.dir") + "/../../jboss-eap-8.1");
        start81Local(Paths.get(eap81HomeProp).toAbsolutePath().normalize());
        log.info("BAMOE 8.1 is UP at {}", client81.getBaseUrl());

        // 4. Deploy container on 8.1 (triggers auto-restore from snapshot)
        client81.deployContainer(CONTAINER_ID, GROUP_ID, ARTIFACT_ID, VERSION);
        log.info("Container {} deployed on BAMOE 8.1", CONTAINER_ID);

        // 5. Verify restored facts before inserting Applicant 4
        int factsRestored = client81.getFactCount(CONTAINER_ID, SESSION_ID);
        assertThat(factsRestored)
                .as("Expected exactly 6 restored facts in BAMOE 8.1 working memory before new insert")
                .isEqualTo(6);

        // 6. Primary Migration Oracle: Insert Applicant 4 (age 17)
        log.info("Inserting Applicant 4 (Age 17) on BAMOE 8.1 on top of restored state...");
        int fired = client81.insertApplicantAndFire(CONTAINER_ID, SESSION_ID, "4", 17);

        assertThat(fired)
                .as("Expected 1 firing (restored session with 3 prior applicants); " +
                    "got %d firings — session state was not restored from 8.0 snapshot", fired)
                .isEqualTo(1);

        int finalFacts = client81.getFactCount(CONTAINER_ID, SESSION_ID);
        assertThat(finalFacts)
                .as("Expected 8 total facts after inserting Applicant 4 into restored session")
                .isEqualTo(8);

        log.info("SUCCESS: Stateful session migration verified! Fired count = 1, Final facts = 8");
    }

    private void start81Local(Path eap81Path) throws Exception {
        Path workDir = snapshotHostDir.resolve("work-eap81");
        Files.createDirectories(workDir);
        String eap81JavaHome = System.getProperty("eap81.java.home", System.getenv("JAVA_HOME_17") != null ? System.getenv("JAVA_HOME_17") : "");
        int portOffset = Integer.parseInt(System.getProperty("eap81.port.offset", "100"));
        eap81LocalServer = new LocalServerHarness("eap81", eap81Path, portOffset, eap81JavaHome,
                snapshot81Dir, workDir, "/kie-server/services/rest/server", KIE_USER, KIE_PASS);

        eap81LocalServer.injectExtension(extensionJarPath);
        eap81LocalServer.installKjar(kjarJarPath);
        eap81LocalServer.start();
        eap81LocalServer.waitUntilReady(Duration.ofMinutes(4));
        client81 = eap81LocalServer.getClient();
    }

    /** Deletes all .ser files in the given directory and creates it if absent. */
    private static void cleanDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (var stream = Files.newDirectoryStream(dir, "*.ser")) {
            for (Path p : stream) {
                Files.deleteIfExists(p);
            }
        }
    }

    @AfterAll
    void cleanup() {
        log.info("Cleaning up integration test environment...");
        if (eap80LocalServer != null) {
            try { eap80LocalServer.stopGracefully(); } catch (Exception ignored) {}
        }
        if (eap81LocalServer != null) {
            try { eap81LocalServer.stopGracefully(); } catch (Exception ignored) {}
        }
        log.info("Stateful Session Migration Integration Test Complete.");
    }
}
