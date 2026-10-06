package com.example.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one local EAP + KIE Server installation: prepares it, injects extension,
 * starts it, stops it gracefully, and exposes log inspection.
 */
public final class LocalServerHarness {

    private static final Logger log = LoggerFactory.getLogger(LocalServerHarness.class);

    final String name;
    final Path eapHome;
    final int portOffset;
    final String javaHome;
    final Path snapshotDir;
    final Path consoleLog;
    final Path serverLog;
    final KieServerClient client;

    private long serverLogOffset;
    private Process process;

    public LocalServerHarness(String name, Path eapHome, int portOffset, String javaHome,
                              Path snapshotDir, Path workDir, String restPath,
                              String user, String password) {
        this.name = name;
        this.eapHome = eapHome;
        this.portOffset = portOffset;
        this.javaHome = javaHome;
        this.snapshotDir = snapshotDir.toAbsolutePath();
        this.consoleLog = workDir.resolve("console-" + name + ".log").toAbsolutePath();
        this.serverLog = eapHome.resolve("standalone/log/server.log");
        int port = 8080 + portOffset;
        String baseUrl = "http://localhost:" + port + restPath;
        this.client = new KieServerClient(baseUrl, user, password);
    }

    public KieServerClient getClient() {
        return client;
    }

    public Path getSnapshotDir() {
        return snapshotDir;
    }

    public void injectExtension(Path extensionJar) throws IOException {
        if (!Files.isRegularFile(extensionJar)) {
            log.warn("{}: Extension jar not found at {}, skipping injection", name, extensionJar);
            return;
        }
        Path war = eapHome.resolve("standalone/deployments/kie-server.war");
        if (Files.isDirectory(war)) {
            Path lib = war.resolve("WEB-INF/lib");
            deleteMatching(lib);
            Files.copy(extensionJar, lib.resolve("session-snapshot-extension.jar"), StandardCopyOption.REPLACE_EXISTING);
            log.info("{}: Injected {} into exploded WAR {}", name, extensionJar.getFileName(), lib);
            return;
        }
        if (!Files.isRegularFile(war)) {
            log.warn("{}: kie-server.war not found at {}", name, war);
            return;
        }
        try (FileSystem fs = FileSystems.newFileSystem(war, (ClassLoader) null)) {
            deleteMatching(fs.getPath("/WEB-INF/lib"));
            Files.copy(extensionJar, fs.getPath("/WEB-INF/lib/session-snapshot-extension.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("{}: Injected {} into packed WAR {}", name, extensionJar.getFileName(), war);
        }
    }

    private static void deleteMatching(Path lib) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(lib, "session-*-extension*.jar")) {
            for (Path p : ds) {
                Files.delete(p);
            }
        }
    }

    public void installKjar(Path kjar) throws IOException {
        if (!Files.isRegularFile(kjar)) {
            log.warn("{}: KJAR not found at {}, skipping install", name, kjar);
            return;
        }
        Path dir = eapHome.resolve("repositories/kie/global/com/example/loan-kjar/1.0.0");
        Files.createDirectories(dir);
        Files.copy(kjar, dir.resolve("loan-kjar.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(kjar, dir.resolve("loan-kjar-1.0.0.jar"), StandardCopyOption.REPLACE_EXISTING);
        log.info("{}: Installed KJAR to {}", name, dir);
    }

    public void start() throws IOException {
        Files.createDirectories(snapshotDir);
        Files.createDirectories(consoleLog.getParent());
        serverLogOffset = Files.exists(serverLog) ? Files.size(serverLog) : 0L;

        List<String> cmd = new ArrayList<>(Arrays.asList(
                eapHome.resolve("bin/standalone.sh").toString(),
                "-c", "standalone-full.xml",
                "-b", "0.0.0.0",
                "-Dkie.session.snapshot.dir=" + snapshotDir));
        if (portOffset != 0) {
            cmd.add("-Djboss.socket.binding.port-offset=" + portOffset);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(eapHome.toFile())
                .redirectErrorStream(true)
                .redirectOutput(consoleLog.toFile());
        applyJavaHome(pb);
        log.info("Starting local server {} on port offset {}...", name, portOffset);
        process = pb.start();
    }

    public void waitUntilReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited early (exit " + process.exitValue()
                        + "). See " + consoleLog);
            }
            if (client.isServerReady()) {
                log.info("{} is UP and responding to REST requests.", name);
                return;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException(name + " not ready after " + timeout + ". See " + consoleLog);
    }

    public void stopGracefully() {
        if (process == null || !process.isAlive()) {
            return;
        }
        log.info("Stopping local server {} gracefully via jboss-cli...", name);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    eapHome.resolve("bin/jboss-cli.sh").toString(),
                    "--connect", "--controller=localhost:" + (9990 + portOffset),
                    "--command=:shutdown")
                    .redirectErrorStream(true);
            applyJavaHome(pb);
            Process cli = pb.start();
            cli.getInputStream().readAllBytes();
            cli.waitFor(60, TimeUnit.SECONDS);
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                killTree();
            }
        } catch (Exception e) {
            log.warn("Graceful shutdown failed for {}, killing process tree: {}", name, e.getMessage());
            killTree();
        }
    }

    public void killTree() {
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public String logText() {
        StringBuilder sb = new StringBuilder();
        try {
            if (Files.exists(consoleLog)) {
                sb.append(new String(Files.readAllBytes(consoleLog), StandardCharsets.UTF_8));
            }
            if (Files.exists(serverLog)) {
                byte[] all = Files.readAllBytes(serverLog);
                int off = (int) (serverLogOffset <= all.length ? serverLogOffset : 0);
                sb.append('\n').append(new String(all, off, all.length - off, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }

    private void applyJavaHome(ProcessBuilder pb) {
        if (javaHome != null && !javaHome.isBlank()) {
            pb.environment().put("JAVA_HOME", javaHome);
        }
    }
}