# The operations UI: a static bundle behind nginx, deployed separately from the service it
# talks to. nginx serves the SPA and proxies /admin/api to the service, so the browser sees
# one origin, the session stays a cookie, and the service needs no CORS. The upstream is a
# variable substituted at container start (nginx's templates mechanism), so the same image
# points at `app:8080` in the single-process stack, `chat:8080` in the split, and the chat
# Service on Kubernetes.
FROM node:22-alpine AS build
WORKDIR /ui
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
ENV ADMIN_API_UPSTREAM=app:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /ui/dist /usr/share/nginx/html
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8084/ || exit 1
