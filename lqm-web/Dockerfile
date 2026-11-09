# The SPA behind Nginx. Build context is the repository root, like the other images:
#   docker build -f lqm-web/Dockerfile -t lqm-web .
#
# One image for every environment: what changes between DEV, HML and PRD (where the API is, which identity provider) is read when the
# container starts, from LQM_API_URL, LQM_OIDC_AUTHORITY and LQM_OIDC_CLIENT_ID, and never baked into the bundle.
FROM node:24-alpine AS build
WORKDIR /web
COPY lqm-web/package.json lqm-web/package-lock.json ./
RUN npm ci
COPY lqm-web/ ./
RUN npm run build

FROM nginx:1-alpine
COPY lqm-web/docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY lqm-web/docker/40-lqm-config.sh /docker-entrypoint.d/40-lqm-config.sh
RUN chmod +x /docker-entrypoint.d/40-lqm-config.sh
COPY --from=build /web/dist /usr/share/nginx/html
# Where the API is, as Nginx reaches it. The browser only ever talks to this server: /api is proxied.
ENV LQM_API_URL=http://api:8080
EXPOSE 80
