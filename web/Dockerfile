FROM oven/bun:1.3.14-alpine AS dependencies
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

FROM dependencies AS build
ARG NEXT_PUBLIC_API_BASE_URL=/api/v1
ARG NEXT_PUBLIC_SOCKET_URL=
ARG NEXT_PUBLIC_MAPTILER_KEY=
ARG NEXT_PUBLIC_DEMO_MODE=false
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_SOCKET_URL=$NEXT_PUBLIC_SOCKET_URL
ENV NEXT_PUBLIC_MAPTILER_KEY=$NEXT_PUBLIC_MAPTILER_KEY
ENV NEXT_PUBLIC_DEMO_MODE=$NEXT_PUBLIC_DEMO_MODE
COPY . .
RUN bun run build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
RUN apk add --no-cache wget && adduser -D -u 10001 familyorbit
COPY --from=build /app/public ./public
COPY --from=build --chown=familyorbit:familyorbit /app/.next/standalone ./
COPY --from=build --chown=familyorbit:familyorbit /app/.next/static ./.next/static
USER familyorbit
EXPOSE 3000
CMD ["node", "server.js"]

