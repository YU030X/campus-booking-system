## Purpose

Define a reproducible, security-conscious container runtime for local verification and a separately gated external deployment acceptance.

## ADDED Requirements

### Requirement: Pinned application images
The deployment plan MUST provide a backend image build targeting JDK 17 and a frontend build image that emits static assets served by Nginx. Base images and service images MUST use non-floating pinned versions and document a digest update strategy; containers SHOULD run as non-root where practical.

#### Scenario: Reproducible image build
- **WHEN** the documented image build is run from a clean checkout
- **THEN** the same declared image references are used, the backend starts with external environment values, and the frontend bundle is served by Nginx without embedding secrets.

### Requirement: Private Compose topology
Compose MUST define Nginx, API, MySQL 8, and Redis services with private database/Redis networks, named persistent volumes, healthchecks, restart policies, dependency ordering, resource limits, and bounded logging. Only Nginx MAY publish host ports, and only 80/443 MAY be public; MySQL and Redis MUST have no host ports.

#### Scenario: Port and restart inspection
- **WHEN** `docker compose config` and a service restart are inspected
- **THEN** only 80/443 are published, internal services resolve over private networks, health-gated startup succeeds, and MySQL/Redis data remains after container recreation.

### Requirement: Nginx edge behavior
Nginx MUST serve the SPA with history fallback, proxy `/api` to the API, set security headers, enforce request-body limits and upstream timeouts, and support certificate/key mounts without committing key material.

#### Scenario: Browser and proxy requests
- **WHEN** a deep-link route, an `/api` request, an oversized body, and an upstream timeout are exercised
- **THEN** the SPA route resolves, the API is proxied, limits/timeouts fail safely, and security headers are present without leaking internal service addresses.

### Requirement: Local versus external deployment gates
Local compose/config/health/restart evidence MAY be completed without infrastructure credentials. Public acceptance MUST require an explicitly user-authorized target, DNS/domain decision, and TLS certificate provision/renewal path; absent those inputs the change MUST remain Draft/gated and MUST NOT claim public deployment, domain, or certificate completion or automatically spend/provision resources.

#### Scenario: No infrastructure authorization
- **WHEN** no user-authorized target or TLS credentials are available
- **THEN** local gates are reportable, external gates are marked not run/blocked, and no public URL or certificate is represented as complete.
