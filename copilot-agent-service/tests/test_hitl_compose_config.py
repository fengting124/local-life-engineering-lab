from pathlib import Path

import yaml


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
REQUIRED_SUBSTITUTION = (
    "${HITL_PAYLOAD_SIGNING_SECRET:?set in ignored infra/.env}"
)


def test_compose_injects_the_same_required_hitl_secret_into_agent_and_copilot():
    compose = yaml.safe_load(
        (REPOSITORY_ROOT / "infra/docker-compose.dev.yml").read_text(
            encoding="utf-8"
        )
    )
    services = compose["services"]

    agent_secret = services["copilot-agent"]["environment"][
        "HITL_PAYLOAD_SIGNING_SECRET"
    ]
    copilot_java_opts = services["locallife-copilot"]["environment"][
        "JAVA_OPTS"
    ]

    assert agent_secret == REQUIRED_SUBSTITUTION
    assert (
        f"-Dhitl.payload-signing.secret={REQUIRED_SUBSTITUTION}"
        in copilot_java_opts
    )


def test_env_examples_document_a_non_secret_hitl_placeholder():
    for relative_path in ("infra/.env.example", "copilot-agent-service/.env.example"):
        content = (REPOSITORY_ROOT / relative_path).read_text(encoding="utf-8")
        assert "HITL_PAYLOAD_SIGNING_SECRET=replace-with-a-random-local-secret" in content
