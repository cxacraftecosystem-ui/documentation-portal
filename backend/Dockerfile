# syntax=docker/dockerfile:1.28

ARG INSTALL_FFMPEG=false


FROM python:3.15-slim-trixie AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential \
 && rm -rf /var/lib/apt/lists/*

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}" \
    VIRTUAL_ENV=/opt/venv

WORKDIR /build

COPY backend/requirements.lock ./requirements.lock

RUN pip install --no-cache-dir -r requirements.lock


FROM node:26-trixie-slim AS node

FROM builder AS prisma

COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && node --version \
 && npm --version

ENV PRISMA_BINARY_CACHE_DIR=/opt/prisma-engines \
    PRISMA_HOME_DIR=/opt/prisma-home \
    PRISMA_NODEENV_CACHE_DIR=/opt/prisma-home/nodeenv \
    PRISMA_USE_GLOBAL_NODE=true

ENV DATABASE_URL=postgresql://placeholder:placeholder@127.0.0.1:5432/placeholder

COPY backend/prisma ./prisma

RUN python -m prisma py fetch \
 && python -m prisma generate --schema=./prisma/schema.prisma \
 && python -c "from prisma import Prisma; Prisma; print('prisma client import OK')"

RUN mkdir -p /opt/prisma-engine \
 && engine="$(find /opt/prisma-engines -type f -size +1M -name 'query-engine-*' | head -n 1)" \
 && test -n "$engine" \
 && cp "$engine" /opt/prisma-engine/query-engine \
 && chmod 0755 /opt/prisma-engine/query-engine \
 && /opt/prisma-engine/query-engine --version

COPY docker/backend/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]


FROM python:3.15-slim-trixie AS runtime

ARG INSTALL_FFMPEG

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/opt/venv/bin:${PATH}" \
    VIRTUAL_ENV=/opt/venv \
    PYTHONPATH=/app \
    PRISMA_QUERY_ENGINE_BINARY=/opt/prisma-engine/query-engine

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates openssl \
 && if [ "$INSTALL_FFMPEG" = "true" ]; then apt-get install -y --no-install-recommends ffmpeg; fi \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 fieldrepo \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home fieldrepo

COPY --from=prisma /opt/venv /opt/venv
COPY --from=prisma /opt/prisma-engine /opt/prisma-engine

WORKDIR /app

COPY backend/app ./app
COPY backend/prisma ./prisma
COPY backend/scripts ./scripts
COPY backend/pyproject.toml ./pyproject.toml
COPY docker/backend/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

RUN mkdir -p /tmp/fieldrepo && chown fieldrepo:fieldrepo /tmp/fieldrepo

USER fieldrepo

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
    CMD ["python", "-c", "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4).status == 200 else 1)"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-proxy-headers"]
