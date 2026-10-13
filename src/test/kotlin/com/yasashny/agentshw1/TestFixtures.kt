package com.yasashny.agentshw1

const val BROKEN_ORDER = """def calculate_total(prices: list[float], discount_percent: float) -> float:
    return sum(prices) - discount_percent
"""

const val FIXED_ORDER = """def calculate_total(prices: list[float], discount_percent: float) -> float:
    if discount_percent < 0 or discount_percent > 100:
        raise ValueError("discount_percent must be between 0 and 100")
    total = sum(prices)
    return total - total * discount_percent / 100
"""

const val TEST_ORDER = """import unittest

from order import calculate_total


class OrderTest(unittest.TestCase):
    def test_discount(self) -> None:
        self.assertEqual(calculate_total([100, 50], 10), 135)

    def test_invalid_discount(self) -> None:
        for value in (-1, 101):
            with self.assertRaises(ValueError):
                calculate_total([100], value)
"""

const val DEFAULT_TEST_POLICY = """
{
  "commands": {
    "allow": [
      "python3 -m unittest -v",
      "python3 -m unittest",
      "python3 --version",
      "ls",
      "pwd",
      "cp a.txt b.txt"
    ],
    "deny": ["rm", "curl", "sudo", "wget"]
  },
  "directories": {
    "allow": ["workspace"],
    "deny": ["workspace/secret_notes"]
  },
  "journal": {
    "ignore": ["__pycache__/", "*.pyc"]
  }
}
"""
