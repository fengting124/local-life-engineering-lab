"""
Structured logging for the Python Agent service.

The service writes JSON logs to stdout. Promtail collects stdout from Docker,
masks sensitive values again at the collector layer, and forwards logs to Loki.
"""
from __future__ import annotations

import logging
import re
import sys
from typing import Any

import structlog
from structlog.contextvars import merge_contextvars

from config.settings import settings


_SENSITIVE_KEY_RE = re.compile(r"(api[_-]?key|authorization|password|passwd|secret|token)", re.IGNORECASE)
_SECRET_VALUE_RE = re.compile(r"(sk-[A-Za-z0-9_\-]{12,}|Bearer\s+[A-Za-z0-9._\-]+)")
_PHONE_RE = re.compile(r"\b(13|14|15|16|17|18|19)\d{9}\b")


def _mask_string(value: str) -> str:
    value = _SECRET_VALUE_RE.sub("***", value)
    value = _PHONE_RE.sub("1**********", value)
    return value


def _sanitize(value: Any) -> Any:
    if isinstance(value, str):
        return _mask_string(value)
    if isinstance(value, dict):
        sanitized: dict[str, Any] = {}
        for key, item in value.items():
            if _SENSITIVE_KEY_RE.search(str(key)):
                sanitized[str(key)] = "***"
            else:
                sanitized[str(key)] = _sanitize(item)
        return sanitized
    if isinstance(value, list):
        return [_sanitize(item) for item in value]
    return value


def _add_service_fields(_logger, _method_name: str, event_dict: dict[str, Any]) -> dict[str, Any]:
    event_dict.setdefault("service", settings.service_name)
    event_dict.setdefault("env", settings.deploy_env)
    return event_dict


def _mask_event(_logger, _method_name: str, event_dict: dict[str, Any]) -> dict[str, Any]:
    return {str(key): _sanitize(value) for key, value in event_dict.items()}


def configure_logging() -> None:
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=logging.INFO)
    structlog.configure(
        processors=[
            merge_contextvars,
            _add_service_fields,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            _mask_event,
            structlog.processors.JSONRenderer(ensure_ascii=False),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )

