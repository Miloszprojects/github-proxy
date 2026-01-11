package com.miloszpodsiadly.githubproxy;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
class GithubService {

    private final GithubClient client;

    GithubService(GithubClient client) {
        this.client = client;
    }

    List<RepositoryResponse> getRepositories(String username) {
        var repos = client.getRepositories(username);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = repos.stream()
                    .filter(repo -> !repo.fork())
                    .map(repo -> {
                        var owner = repo.owner().login();
                        var branchesFuture = CompletableFuture.supplyAsync(
                                () -> client.getBranches(owner, repo.name()),
                                executor
                        );
                        return new RepoTask(repo.name(), owner, branchesFuture);
                    })
                    .toList();

            return tasks.stream()
                    .map(t -> new RepositoryResponse(
                            t.repoName(),
                            t.ownerLogin(),
                            t.branchesFuture().join()
                    ))
                    .toList();
        }
    }

    private record RepoTask(
            String repoName,
            String ownerLogin,
            CompletableFuture<List<Branch>> branchesFuture
    ) {}
}
