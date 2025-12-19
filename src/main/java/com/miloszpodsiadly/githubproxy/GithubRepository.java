package com.miloszpodsiadly.githubproxy;

record GithubRepository(
        String name,
        boolean fork,
        Owner owner
) {
    record Owner(String login) {}
}

