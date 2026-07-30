FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk@sha256:da9d3a4f7650db39b918fc5a2c3da76556fb8cc8e5f3767cdea0bb409286951a

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    jq \
    python3 \
    python3-pip \
    python3-pytest \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir pytest-json-ctrf --break-system-packages || true

COPY task/environment/data /app/data
COPY task/environment/data/ledger_events.json /app/ledger_events.json

RUN mkdir -p /app/bin /logs/verifier
