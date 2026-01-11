package com.miloszpodsiadly.githubproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GithubProxyApplicationTests {

    private static final String USER = "MiloszPodsiadly";

    // We simulate GitHub latency: each request takes 1000ms.
    private static final int GITHUB_DELAY_MS = 1000;

    private static final WireMockServer wireMockServer;

    static {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("github.base-url", wireMockServer::baseUrl);
    }

    @Value("${local.server.port}")
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void makes3GithubRequests_andProcessesIn2to3Seconds_when2NonForkReposFetchBranchesInParallel() throws Exception {
        /* ===== GIVEN =====
         1) GitHub endpoint /users/{username}/repos returns 3 repositories,
            but one of them is a fork (it should be filtered out).
         2) Each request to WireMock has a fixedDelay of 1000ms.

         So we expect the application to execute:
         - 1 request: /users/{username}/repos (1000ms)
         - 2 requests in parallel: /repos/{owner}/{repo}/branches (1000ms total, because they're in parallel)
         Total time: about 2000ms (+overhead!!), so an assertion of 2000–3000ms. */
        stubGithubReposWithDelay(USER, """
                [
                  { "name": "RepoA",    "fork": false, "owner": { "login": "MiloszPodsiadly" } },
                  { "name": "RepoB",    "fork": false, "owner": { "login": "MiloszPodsiadly" } },
                  { "name": "RepoFork", "fork": true,  "owner": { "login": "MiloszPodsiadly" } }
                ]
                """);

        // Branches for two non-fork repositories (also with fixedDelay = 1000ms)
        stubGithubBranchesWithDelay(USER, "RepoA", """
                [
                  { "name": "main", "commit": { "sha": "de2bc6e3dbe9d7ce7f1ec48d5230e5a7a201864d" } }
                ]
                """);

        stubGithubBranchesWithDelay(USER, "RepoB", """
                [
                  { "name": "master", "commit": { "sha": "21b819e26224cda02d1f1ac28bc9629da84d6cfe" } }
                ]
                """);

        // ===== WHEN =====
        // We curl our endpoint: /users/{username}/repositories and measure the total execution time.
        var stopWatch = new StopWatch();
        stopWatch.start();
        var response = callRepositories(USER);
        stopWatch.stop();

        RepositoryResponse[] body = readJson(response, RepositoryResponse[].class);
        long elapsedMs = stopWatch.getTime();

        /* ===== THEN =====
         1) We return only 2 repos (the fork is ignored).
         2) Execution time is within 2000–3000ms (proof that the branches were executed in parallel).
         3) A total of exactly 3 requests to "GitHub" (WireMock) were made:
            1x repos + 2x branches.*/
        assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body).hasSize(2),
                () -> assertThat(elapsedMs).isBetween(2000L, 3000L),
                () -> verify(3, getRequestedFor(urlMatching(".*"))),

                // additionally: readability – we know which requests were sent and that we are not downloading the fork
                () -> verify(1, getRequestedFor(urlEqualTo("/users/" + USER + "/repos"))),
                () -> verify(1, getRequestedFor(urlEqualTo("/repos/" + USER + "/RepoA/branches"))),
                () -> verify(1, getRequestedFor(urlEqualTo("/repos/" + USER + "/RepoB/branches"))),
                () -> verify(0, getRequestedFor(urlEqualTo("/repos/" + USER + "/RepoFork/branches")))
        );
    }

    @Test
    void returns404WithExpectedBodyWhenGithubUserDoesNotExist() throws Exception {
        // ===== GIVEN =====
        // GitHub returns 404 for /users/{username}/repos (simulating "Not Found").
        String missingUser = USER + "yyyyyyy";
        stubFor(get(urlEqualTo("/users/" + missingUser + "/repos"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("{\"message\":\"Not Found\"}")
                        .withFixedDelay(GITHUB_DELAY_MS)));

        // ===== WHEN =====
        var response = callRepositoriesAllowing4xx(missingUser);

        // ===== THEN =====
        ErrorResponse body = readJson(response, ErrorResponse.class);

        assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(body.status()).isEqualTo(404),
                () -> assertThat(body.message()).isEqualTo("User not found"),
                () -> verify(1, getRequestedFor(urlEqualTo("/users/" + missingUser + "/repos")))
        );
    }

    private RestClient appClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private ResponseEntity<String> callRepositories(String username) {
        return appClient()
                .get()
                .uri("/users/{username}/repositories", username)
                .retrieve()
                .toEntity(String.class);
    }

    private ResponseEntity<String> callRepositoriesAllowing4xx(String username) {
        return appClient()
                .get()
                .uri("/users/{username}/repositories", username)
                .exchange((req, res) -> ResponseEntity
                        .status(res.getStatusCode())
                        .body(res.getBody() != null ? new String(res.getBody().readAllBytes()) : null)
                );
    }

    private <T> T readJson(ResponseEntity<String> response, Class<T> type) throws IOException {
        assertThat(response.getBody()).isNotNull();
        return objectMapper.readValue(response.getBody(), type);
    }

    private static void stubGithubReposWithDelay(String username, String jsonBody) {
        stubFor(get(urlEqualTo("/users/" + username + "/repos"))
                .willReturn(okJson(jsonBody).withFixedDelay(GITHUB_DELAY_MS)));
    }

    private static void stubGithubBranchesWithDelay(String owner, String repo, String jsonBody) {
        stubFor(get(urlEqualTo("/repos/" + owner + "/" + repo + "/branches"))
                .willReturn(okJson(jsonBody).withFixedDelay(GITHUB_DELAY_MS)));
    }
}
