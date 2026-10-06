package com.example.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST client for KIE Server operations and session snapshotting.
 */
public class KieServerClient {

    private static final Logger log = LoggerFactory.getLogger(KieServerClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String authHeader;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public KieServerClient(String baseUrl, String user, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = Credentials.basic(user, password);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Checks if KIE Server is responsive and reports UP.
     */
    public boolean isServerReady() {
        try {
            Request req = new Request.Builder()
                    .url(baseUrl)
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deploys a KIE container.
     */
    public String deployContainer(String containerId, String groupId, String artifactId, String version) {
        String url = baseUrl + "/containers/" + containerId;
        String json = String.format("{\"container-id\":\"%s\",\"release-id\":{\"group-id\":\"%s\",\"artifact-id\":\"%s\",\"version\":\"%s\"}}",
                containerId, groupId, artifactId, version);

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .put(RequestBody.create(json, JSON))
                .build();

        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("Failed to deploy container " + containerId + ": HTTP " + resp.code() + " - " + body);
            }
            log.info("Container {} deployed successfully", containerId);
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException("Error deploying container " + containerId, e);
        }
    }

    /**
     * Disposes a KIE container.
     */
    public void disposeContainer(String containerId) {
        String url = baseUrl + "/containers/" + containerId;
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .delete()
                .build();

        try (Response resp = client.newCall(req).execute()) {
            log.info("Disposed container {}: HTTP {}", containerId, resp.code());
        } catch (Exception e) {
            log.warn("Failed to dispose container {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * Inserts an Applicant and LoanApplication into the stateful session, fires rules, and returns fired count.
     */
    public int insertApplicantAndFire(String containerId, String sessionId, String applicantId, int age) {
        String url = baseUrl + "/containers/instances/" + containerId;
        String commands = String.format("{\"lookup\":\"%s\",\"commands\":["
                + "{\"insert\":{\"object\":{\"com.example.model.Applicant\":{\"id\":\"%s\",\"age\":%d}}}},"
                + "{\"insert\":{\"object\":{\"com.example.model.LoanApplication\":{\"applicantId\":\"%s\"}}}},"
                + "{\"fire-all-rules\":{\"out-identifier\":\"fired\"}}]}",
                sessionId, applicantId, age, applicantId);

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .post(RequestBody.create(commands, JSON))
                .build();

        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("Failed to insert and fire rules: HTTP " + resp.code() + " - " + body);
            }
            JsonNode root = mapper.readTree(body);
            JsonNode results = root.findValue("results");
            if (results != null && results.isArray()) {
                for (JsonNode entry : results) {
                    if ("fired".equals(entry.path("key").asText())) {
                        return entry.path("value").asInt(-1);
                    }
                }
            }
            throw new IllegalStateException("Missing 'fired' in response: " + body);
        } catch (IOException e) {
            throw new UncheckedIOException("Error inserting applicant " + applicantId, e);
        }
    }

    /**
     * Queries total facts currently in working memory using get-objects.
     */
    public int getFactCount(String containerId, String sessionId) {
        String url = baseUrl + "/containers/instances/" + containerId;
        String commands = String.format("{\"lookup\":\"%s\",\"commands\":[{\"get-objects\":{\"out-identifier\":\"facts\"}}]}", sessionId);

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .post(RequestBody.create(commands, JSON))
                .build();

        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("Failed to query objects: HTTP " + resp.code() + " - " + body);
            }
            JsonNode root = mapper.readTree(body);
            JsonNode factsNode = null;
            JsonNode results = root.findValue("results");
            if (results != null && results.isArray()) {
                for (JsonNode entry : results) {
                    if ("facts".equals(entry.path("key").asText())) {
                        factsNode = entry.path("value");
                        break;
                    }
                }
            }
            if (factsNode == null) {
                factsNode = root.findValue("facts");
            }
            if (factsNode != null && factsNode.isArray()) {
                return factsNode.size();
            }
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Error getting fact count for container " + containerId, e);
        }
    }

    /**
     * Calls the extension snapshot endpoint: POST /server/containers/{containerId}/sessions/{sessionId}/snapshot
     */
    public void snapshotSession(String containerId, String sessionId) {
        String url = baseUrl + "/containers/" + containerId + "/sessions/" + sessionId + "/snapshot";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .post(RequestBody.create("{}", JSON))
                .build();

        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("Failed to take snapshot: HTTP " + resp.code() + " - " + body);
            }
            log.info("Session snapshot created: {}", body);
        } catch (IOException e) {
            throw new UncheckedIOException("Error snapshotting session " + sessionId, e);
        }
    }

    /**
     * Checks if a snapshot exists via GET /server/containers/{containerId}/sessions/{sessionId}/snapshot
     */
    public boolean isSnapshotPresent(String containerId, String sessionId) {
        String url = baseUrl + "/containers/" + containerId + "/sessions/" + sessionId + "/snapshot";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                return false;
            }
            JsonNode root = mapper.readTree(body);
            return root.path("exists").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}