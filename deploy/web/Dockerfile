# T13 deploy slice 1 - frontend build + Nginx edge image.
# Multi-stage: pinned Node builder runs `npm ci` against the repository-pinned
# package-lock.json, then Vite output is served by the pinned non-root
# nginx-unprivileged image. The Nginx site config is NOT baked in; compose mounts
# ./nginx/default.conf (relative to deploy/) read-only at runtime (see deploy/compose.yml).
# Status: statically authored only; no image built, no digest recorded yet.

# Both ARGs sit before the first FROM so they are global-scope and safely
# substitutable in every subsequent FROM of this multi-stage build.
ARG NODE_BUILD_IMAGE=node:22.14.0-alpine
ARG NGINX_RUNTIME_IMAGE=nginxinc/nginx-unprivileged:1.27.4-alpine

FROM ${NODE_BUILD_IMAGE} AS webbuild

WORKDIR /build

# COPY lists package-lock.json explicitly: if the lockfile is missing the build
# fails here (fail closed). There is deliberately NO `npm install` fallback;
# a missing lock is a booking-web owner blocker (see deploy/README.md).
COPY package.json package-lock.json ./

RUN test -s package-lock.json || (echo "FAIL CLOSED: package-lock.json missing or empty; npm install fallback is forbidden" && exit 1)

RUN npm ci --no-audit --no-fund

# Source copy is selective so a host node_modules/dist directory can never leak
# into the build layer.
COPY index.html vite.config.js ./
COPY src ./src

RUN npm run build

FROM ${NGINX_RUNTIME_IMAGE}

COPY --from=webbuild /build/dist /usr/share/nginx/html

USER 101
EXPOSE 8080
