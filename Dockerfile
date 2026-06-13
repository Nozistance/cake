FROM eclipse-temurin:25-jdk

LABEL org.opencontainers.image.source=https://github.com/Nozistance/cake

WORKDIR /app

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    rm -f /etc/apt/apt.conf.d/docker-clean; \
    apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-venv ffmpeg curl unzip ca-certificates

RUN curl -L -o /tmp/clj.sh https://download.clojure.org/install/linux-install.sh \
 && bash /tmp/clj.sh && rm /tmp/clj.sh

RUN --mount=type=cache,target=/dl,sharing=locked \
    set -eux; f=/dl/deno.zip; \
    [ -s "$f" ] || { curl -fL --retry 5 --retry-delay 3 --retry-all-errors -C - -o "$f.part" https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip && mv "$f.part" "$f"; }; \
    unzip -o "$f" -d /usr/local/bin; chmod +x /usr/local/bin/deno; deno --version

RUN --mount=type=cache,target=/root/.cache/pip \
    python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --upgrade pip yt-dlp \
 && /opt/venv/bin/python3 -c "import yt_dlp; print('yt-dlp', yt_dlp.version.__version__)"

COPY deps.edn .
RUN --mount=type=cache,target=/cache/m2,sharing=locked \
    set -eux; clojure -Sdeps '{:mvn/local-repo "/cache/m2"}' -P; \
    mkdir -p /root/.m2/repository; cp -a /cache/m2/. /root/.m2/repository/

COPY . .

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PYTHONUTF8=1 \
    CONFIG=/app/config/parameters.edn \
    PROFILE=prod \
    DATA_DIR=/data \
    FILE_DIR=/assets \
    LOG_DIR=/data/logs \
    PYTHON_BIN=/opt/venv/bin/python3 \
    PORT=8080

VOLUME ["/data", "/assets"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c "</dev/tcp/127.0.0.1/$PORT" || exit 1

CMD ["clojure", "-M:run"]
