#!/bin/bash
set -e

mkdir -p /logs/verifier
REWARD_FILE="/logs/verifier/reward.txt"
CTRF_FILE="/logs/verifier/ctrf.json"

# Inject hidden test dataset
cp /tests/hidden_ledger_events.json /app/ledger_events.json

# Execute candidate script or solution if present
if [ -f "/app/run.sh" ]; then
    chmod +x /app/run.sh
    /app/run.sh || true
elif [ -f "/solution/solve.sh" ]; then
    chmod +x /solution/solve.sh
    /solution/solve.sh || true
fi

# Run pytest to evaluate output
pytest /tests/test_outputs.py --ctrf "$CTRF_FILE" > /logs/verifier/pytest.log 2>&1

if [ $? -eq 0 ]; then
    echo "1" > "$REWARD_FILE"
else
    echo "0" > "$REWARD_FILE"
fi

exit 0
