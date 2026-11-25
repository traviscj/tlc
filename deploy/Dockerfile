# Build first on the host:  (. bin/activate-hermit && ./gradlew test installDist)
# then from the repo root:  docker build -f deploy/Dockerfile -t tlc:local .
FROM amazoncorretto:21-alpine
WORKDIR /app
COPY build/install/tlc/ /app/
ENV TLC_CONFIG=/config/tlc.yaml
EXPOSE 8790
ENTRYPOINT ["/app/bin/tlc"]
