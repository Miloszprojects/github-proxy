package com.miloszpodsiadly.githubproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GithubProxyApplicationTests {

    private static final String USER = "MiloszPodsiadly";

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
    void returnsNonForkReposWithBranchesAndLastCommitSha() throws Exception {
        stubGithubRepos(USER, """
                [
                  { "name": "CareerHub", "fork": false, "owner": { "login": "MiloszPodsiadly" } }
                ]
                """);

        stubGithubBranches(USER, "CareerHub", """
                [
                  { "name": "main", "commit": { "sha": "de2bc6e3dbe9d7ce7f1ec48d5230e5a7a201864d" } }
                ]
                """);

        var response = callRepositories(USER);

        RepositoryResponse[] body = readJson(response, RepositoryResponse[].class);

        assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body).hasSize(1),
                () -> assertRepository(body[0], "CareerHub", USER),
                () -> assertThat(body[0].branches()).hasSize(1),
                () -> assertBranchHasSha(body[0].branches().getFirst(), "main"),
                () -> verify(1, getRequestedFor(urlEqualTo("/users/" + USER + "/repos"))),
                () -> verify(1, getRequestedFor(urlEqualTo("/repos/" + USER + "/CareerHub/branches")))
        );
    }

    @Test
    void skipsForkReposAndDoesNotFetchBranchesForForks() throws Exception {
        stubGithubRepos(USER, """
                [
                  { "name": "ForkedRepo", "fork": true,  "owner": { "login": "MiloszPodsiadly" } },
                  { "name": "Library",    "fork": false, "owner": { "login": "MiloszPodsiadly" } }
                ]
                """);

        stubGithubBranches(USER, "Library", """
                [
                  { "name": "master", "commit": { "sha": "21b819e26224cda02d1f1ac28bc9629da84d6cfe" } }
                ]
                """);

        var response = callRepositories(USER);

        RepositoryResponse[] body = readJson(response, RepositoryResponse[].class);

        assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body).hasSize(1),
                () -> assertRepository(body[0], "Library", USER),
                () -> assertThat(body[0].branches()).hasSize(1),
                () -> assertBranchHasSha(body[0].branches().getFirst(), "master"),
                () -> verify(1, getRequestedFor(urlEqualTo("/users/" + USER + "/repos"))),
                () -> verify(0, getRequestedFor(urlEqualTo("/repos/" + USER + "/ForkedRepo/branches"))),
                () -> verify(1, getRequestedFor(urlEqualTo("/repos/" + USER + "/Library/branches")))
        );
    }

    @Test
    void returns404WithExpectedBodyWhenGithubUserDoesNotExist() throws Exception {
        String missingUser = USER + "yyyyyyy";
        stubFor(get(urlEqualTo("/users/" + missingUser + "/repos"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("{\"message\":\"Not Found\"}")));

        var response = callRepositoriesAllowing4xx(missingUser);

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

    private static void stubGithubRepos(String username, String jsonBody) {
        stubFor(get(urlEqualTo("/users/" + username + "/repos"))
                .willReturn(okJson(jsonBody)));
    }

    private static void stubGithubBranches(String owner, String repo, String jsonBody) {
        stubFor(get(urlEqualTo("/repos/" + owner + "/" + repo + "/branches"))
                .willReturn(okJson(jsonBody)));
    }

    private static void assertRepository(RepositoryResponse repo, String expectedName, String expectedOwnerLogin) {
        assertAll(
                () -> assertThat(repo.repositoryName()).isEqualTo(expectedName),
                () -> assertThat(repo.ownerLogin()).isEqualTo(expectedOwnerLogin),
                () -> assertThat(repo.branches()).isNotNull()
        );
    }

    private static void assertBranchHasSha(Branch branch, String expectedBranchName) {
        assertAll(
                () -> assertThat(branch).isNotNull(),
                () -> assertThat(branch.name()).isEqualTo(expectedBranchName),
                () -> assertThat(branch.commit()).isNotNull(),
                () -> assertThat(branch.commit().sha()).isNotBlank(),
                () -> assertThat(branch.commit().sha()).matches("^[a-f0-9]{40}$")
        );
    }
}
