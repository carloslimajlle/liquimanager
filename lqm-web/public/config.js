// Runtime configuration. In the container this file is rewritten at start (lqm-web/docker/40-lqm-config.sh); in development it stays empty
// and the app reads VITE_OIDC_AUTHORITY from the environment of the dev server instead.
window.__LQM_CONFIG__ = {};
