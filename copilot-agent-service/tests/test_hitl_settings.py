import pytest
from pydantic import ValidationError

from config.settings import Settings


def test_settings_reject_missing_hitl_payload_signing_secret(monkeypatch):
    monkeypatch.delenv("HITL_PAYLOAD_SIGNING_SECRET", raising=False)

    with pytest.raises(ValidationError, match="hitl_payload_signing_secret"):
        Settings(_env_file=None, debug=False)


def test_settings_accept_explicit_hitl_payload_signing_secret(monkeypatch):
    monkeypatch.setenv("HITL_PAYLOAD_SIGNING_SECRET", "test-only-hitl-key")

    configured = Settings(_env_file=None, debug=False)

    assert configured.hitl_payload_signing_secret == "test-only-hitl-key"


def test_settings_reject_blank_hitl_payload_signing_secret(monkeypatch):
    monkeypatch.setenv("HITL_PAYLOAD_SIGNING_SECRET", "   ")

    with pytest.raises(ValidationError, match="hitl_payload_signing_secret"):
        Settings(_env_file=None, debug=False)
