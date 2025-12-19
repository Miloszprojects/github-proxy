package com.miloszpodsiadly.githubproxy;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
class GithubService {

    private final GithubClient client;

    GithubService(GithubClient client) {
        this.client = client;
    }

    List<RepositoryResponse> getRepositories(String username) {
        var repos = client.getRepositories(username);

        return repos.stream()
                .filter(repo -> !repo.fork())
                .map(repo -> {
                    var owner = repo.owner().login();
                    var branches = client.getBranches(owner, repo.name());
                    return new RepositoryResponse(
                            repo.name(),
                            owner,
                            branches
                    );
                })
                .toList();
    }
}


