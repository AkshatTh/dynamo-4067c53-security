import json
import os
import pytest

def test_balances_output():
    """Verify generated /app/balances.json matches ground truth expected balances."""
    assert os.path.exists("/app/balances.json"), "/app/balances.json output file was not created"
    
    with open("/app/balances.json", "r") as f:
        actual = json.load(f)

    expected_path = "/tests/expected_balances.json"
    assert os.path.exists(expected_path), f"Expected ground truth missing at {expected_path}"
    
    with open(expected_path, "r") as f:
        expected = json.load(f)

    assert actual == expected, f"Output mismatch!\nExpected:\n{json.dumps(expected, indent=2)}\nGot:\n{json.dumps(actual, indent=2)}"
