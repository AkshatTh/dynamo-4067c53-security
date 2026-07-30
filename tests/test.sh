#!/bin/bash
set -e

mkdir -p /logs/verifier
REWARD_FILE="/logs/verifier/reward.txt"
CTRF_FILE="/logs/verifier/ctrf.json"

# Default result
REWARD=0
TEST_STATUS="failed"
ERROR_MESSAGE=""

# Step 1: Inject hidden dataset
if [ -f "tests/hidden_ledger_events.json" ]; then
    cp tests/hidden_ledger_events.json /app/ledger_events.json
elif [ -f "/app/tests/hidden_ledger_events.json" ]; then
    cp /app/tests/hidden_ledger_events.json /app/ledger_events.json
fi

# Step 2: Execute candidate script
if [ -f "/app/run.sh" ]; then
    chmod +x /app/run.sh
    if /app/run.sh; then
        echo "Execution of /app/run.sh succeeded."
    else
        ERROR_MESSAGE="Execution of /app/run.sh failed."
    fi
else
    ERROR_MESSAGE="/app/run.sh not found."
fi

# Step 3: Evaluate output against expected balances
EXPECTED_FILE="tests/expected_balances.json"
if [ ! -f "$EXPECTED_FILE" ]; then
    EXPECTED_FILE="/app/tests/expected_balances.json"
fi

OUTPUT_FILE="/app/balances.json"

if [ -f "$OUTPUT_FILE" ] && [ -f "$EXPECTED_FILE" ]; then
    # Compare canonical sorted JSON strings
    CANON_OUTPUT=$(jq -S . "$OUTPUT_FILE" 2>/dev/null || echo "invalid_output")
    CANON_EXPECTED=$(jq -S . "$EXPECTED_FILE" 2>/dev/null || echo "invalid_expected")

    if [ "$CANON_OUTPUT" != "invalid_output" ] && [ "$CANON_OUTPUT" = "$CANON_EXPECTED" ]; then
        REWARD=1
        TEST_STATUS="passed"
        echo "Verification PASSED: /app/balances.json matches expected ground truth perfectly."
    else
        echo "Verification FAILED: /app/balances.json does not match expected output."
        echo "Diff:"
        diff -u <(echo "$CANON_EXPECTED") <(echo "$CANON_OUTPUT") || true
        ERROR_MESSAGE="Output balances mismatch expected ground truth."
    fi
else
    echo "Verification FAILED: Output file $OUTPUT_FILE or expected file $EXPECTED_FILE missing."
    ERROR_MESSAGE="Output file or expected file missing."
fi

# Step 4: Write reward.txt
echo "$REWARD" > "$REWARD_FILE"
echo "Wrote $REWARD to $REWARD_FILE"

# Step 5: Write ctrf.json
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat <<EOF > "$CTRF_FILE"
{
  "results": {
    "tool": {
      "name": "dynamo-verifier"
    },
    "summary": {
      "tests": 1,
      "passed": $( [ "$REWARD" -eq 1 ] && echo 1 || echo 0 ),
      "failed": $( [ "$REWARD" -eq 0 ] && echo 1 || echo 0 ),
      "pending": 0,
      "skipped": 0,
      "other": 0,
      "start": 0,
      "stop": 0
    },
    "tests": [
      {
        "name": "Bitemporal Expense Ledger State Machine Resolution",
        "status": "$TEST_STATUS",
        "duration": 0,
        "rawStatus": "$TEST_STATUS",
        "message": "$ERROR_MESSAGE"
      }
    ]
  }
}
EOF
echo "Wrote CTRF report to $CTRF_FILE"

exit 0
