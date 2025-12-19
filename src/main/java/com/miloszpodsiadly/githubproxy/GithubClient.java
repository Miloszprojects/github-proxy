package com.miloszpodsiadly.githubproxy;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
class GithubClient {

    private final RestClient restClient;

    GithubClient(RestClient restClient) {
        this.restClient = restClient;
    }

    List<GithubRepository> getRepositories(String username) {
        try {
            var repos = restClient.get()
                    .uri("/users/{username}/repos", username)
                    .retrieve()
                    .body(GithubRepository[].class);

            return repos == null ? List.of() : List.of(repos);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new GithubUserNotFoundException();
        }
    }

    List<Branch> getBranches(String owner, String repo) {
        var branches = restClient.get()
                .uri("/repos/{owner}/{repo}/branches", owner, repo)
                .retrieve()
                .body(Branch[].class);

        return branches == null ? List.of() : List.of(branches);
    }
}

