FROM golang:1.26-alpine AS build
WORKDIR /src
RUN apk add --no-cache ca-certificates git
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/family-orbit-api ./cmd/server

FROM alpine:3.24
RUN apk add --no-cache ca-certificates tzdata wget && adduser -D -u 10001 familyorbit
COPY --from=build /out/family-orbit-api /usr/local/bin/family-orbit-api
USER familyorbit
EXPOSE 4000
ENTRYPOINT ["family-orbit-api"]
CMD ["api"]

