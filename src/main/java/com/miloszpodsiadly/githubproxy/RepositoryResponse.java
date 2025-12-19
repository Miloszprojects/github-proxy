package com.miloszpodsiadly.githubproxy;

import java.util.List;

record RepositoryResponse(
        String repositoryName,
        String ownerLogin,
        List<Branch> branches
) {}

