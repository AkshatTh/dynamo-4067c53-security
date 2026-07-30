FROM eclipse-temurin:21-jdk@sha256:da9d3a4f7650db39b918fc5a2c3da76556fb8cc8e5f3767cdea0bb409286951a

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    jq \
    python3 \
    && rm -rf /var/lib/apt/lists/*

COPY environment/src /app/src
COPY environment/run.sh /app/run.sh
COPY environment/ledger_events.json /app/ledger_events.json

RUN chmod +x /app/run.sh

CMD ["/bin/bash"]
