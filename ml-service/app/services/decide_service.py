"""
Decide Service — pure logic for Phase 5-3 Contextual Bandit.
No FastAPI imports.
"""
from typing import Optional


def derive_context_key(explicit_key: str, cluster_id: Optional[int]) -> str:
    """
    Phase 5-3: Contextual Bandit — derive effective context_key.

    When cluster_id is provided and explicit_key is "global", route to
    cluster-specific Thompson arms so each archetype gets its own posterior.
    """
    if cluster_id is not None and explicit_key == "global":
        return f"cluster_{cluster_id}"
    return explicit_key
