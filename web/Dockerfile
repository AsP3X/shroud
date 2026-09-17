# syntax=docker/dockerfile:1

FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache wget
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY nginx-http-map.conf /etc/nginx/conf.d/00-http-map.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY api-proxy.conf /etc/nginx/api-proxy.conf
# nginx image envsubst only replaces defined environment variables.
ENV API_UPSTREAM=http://api:8080
ENV MAX_UPLOAD_BYTES=26214400
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
