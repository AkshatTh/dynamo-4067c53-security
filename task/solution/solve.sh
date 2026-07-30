#!/bin/bash
set -e

mkdir -p /app/bin
javac -d /app/bin /solution/Main.java
java -cp /app/bin Main /app/ledger_events.json /app/balances.json
