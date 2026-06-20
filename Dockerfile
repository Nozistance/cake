FROM ghcr.io/graalvm/native-image-community:24 AS build

WORKDIR /app

RUN microdnf install -y curl findutils gcc glibc-static zlib-static

RUN curl -L -o /tmp/clj.sh https://download.clojure.org/install/linux-install.sh \
 && bash /tmp/clj.sh && rm /tmp/clj.sh

COPY deps.edn build.clj ./
RUN --mount=type=cache,target=/cache/m2,sharing=locked \
    set -eux; clojure -Sdeps '{:mvn/local-repo "/cache/m2"}' -P -A:build; \
    mkdir -p /root/.m2/repository; cp -a /cache/m2/. /root/.m2/repository/

COPY src src
COPY config config
COPY resources resources

RUN clojure -T:build uber \
 && native-image --no-fallback \
      -H:+UnlockExperimentalVMOptions -H:+StaticExecutableWithDynamicLibC \
      -jar target/cake.jar -o target/cake

FROM alpine:3

LABEL org.opencontainers.image.source=https://github.com/Nozistance/cake

WORKDIR /app

RUN apk add --no-cache python3 py3-pip ffmpeg ca-certificates gcompat \
 && python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir yt-dlp \
 && /opt/venv/bin/python3 -c "import yt_dlp; print('yt-dlp', yt_dlp.version.__version__)"

COPY --from=build /app/target/cake /app/cake
COPY --from=build /app/config /app/config

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PYTHONUTF8=1 \
    CONFIG=/app/config/parameters.edn \
    PROFILE=prod \
    FILE_DIR=/assets \
    REMOTE_FILE_DIR=/assets \
    LOG_DIR=/data/logs \
    PYTHON_BIN=/opt/venv/bin/python3 \
    PORT=8080

VOLUME ["/data", "/assets"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD nc -z 127.0.0.1 8080 || exit 1

CMD ["/app/cake"]
