package com.miloszpodsiadly.githubproxy;

record Branch(
        String name,
        Commit commit
) {
    record Commit(String sha) {}
}
