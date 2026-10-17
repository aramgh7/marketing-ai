import pytest


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "slow: marks tests that run real PyMC MCMC sampling (skip with -m 'not slow')",
    )
