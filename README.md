# Stateful KIE Session Migration Integration Test (BAMOE 8.0 -> 8.1)

Automated JUnit 5 integration test suite proving runtime stateful KIE session migration from **BAMOE 8.0 on EAP 7.4** to **BAMOE 8.1 on EAP 8.1**.

## Dual-Execution Mode: Local EAP vs Docker

The integration test automatically adapts to your environment:
1. **Local EAP Mode (Default)**: Automatically detects local JBoss EAP installations at `../jboss-eap-7.4` and `../jboss-eap-8.1`, manages their lifecycle with port offsets, injects the snapshot extension, and executes the migration.
2. **Docker Mode**: If Docker images are specified via `-Dbamoe.80.image=...` and `-Dbamoe.81.image=...`, Testcontainers orchestrates the containers with volume mounts.

## Overview

1. **BAMOE 8.0 (EAP 7.4)**:
   - Starts with `SessionSnapshotExtension` injected into `kie-server.war`.
   - Deploys `loan-kjar:1.0.0` with stateful session `KBaseKS_stateful`.
   - Inserts 3 Applicants (ages 20, 21, 35) & 3 LoanApplications -> asserts 1 rule firing each and 6 total working memory facts.
   - Triggers `POST /server/containers/{containerId}/sessions/{sessionId}/snapshot` -> writes binary `.ser` Protobuf snapshot to the staging directory.
   - Stops gracefully.

2. **BAMOE 8.1 (EAP 8.1)**:
   - Copies snapshot `.ser` to the 8.1 staging directory.
   - Starts BAMOE 8.1 on port offset `100` (port `8180`).
   - Deploys `loan-kjar:1.0.0` -> extension automatically unmarshalls working memory from `.ser`.
   - Asserts working memory initially contains the 6 restored facts.
   - Inserts Applicant 4 (age 17) -> asserts **EXACTLY 1** rule firing (proves prior 3 matches are not re-evaluated or lost).
   - Asserts total facts == 8.

## Project Structure

```
kie-server-session-migration-integration-test/
├── pom.xml                                   # Root parent POM
├── loan-kjar/                                # Rules KJAR (DRL, Applicant model, kmodule.xml)
│   ├── pom.xml
│   └── src/main/resources/rules/loan-application-age-limit.drl
├── session-snapshot-extension/               # KIE Server Extension for REST & auto-restore snapshotting
│   ├── pom.xml
│   └── src/main/java/com/example/ks/extension/
│       ├── SessionSnapshotExtension.java
│       └── SessionSnapshotAppComponents.java
└── migration-it/                             # JUnit 5 execution suite
    ├── pom.xml
    └── src/test/
        ├── java/com/example/it/
        │   ├── KieServerClient.java
        │   ├── LocalServerHarness.java
        │   └── StatefulSessionMigrationIT.java
        └── resources/
            └── logback-test.xml
```

## Running the Integration Test

### Run with Local EAP Installations (Default)
```bash
mvn clean verify
```

To specify custom paths to your local EAP instances:
```bash
mvn clean verify \
  -Deap80.home=/path/to/jboss-eap-7.4 \
  -Deap81.home=/path/to/jboss-eap-8.1
```

### Run with Docker / Testcontainers
```bash
mvn clean verify \
  -Dbamoe.80.image=your-registry/bamoe-80-eap:latest \
  -Dbamoe.81.image=your-registry/bamoe-81-eap:latest
```
