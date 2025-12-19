# github-proxy

A simple proxy application written in Java 25 and Spring Boot 4.0.0.  
The application exposes a REST endpoint that returns a list of GitHub repositories
for a given user (excluding forks), together with their branches and the SHA of the
latest commit for each branch.

Backing API: GitHub REST API v3 (https://developer.github.com/v3)

---

## Technology stack
- Java 25
- Spring Boot 4.0.0
- Gradle
- Integration tests: WireMock

---

## Configuration
The application uses a single configuration property:

```properties
github.base-url=https://api.github.com
