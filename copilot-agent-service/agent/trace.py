"""Tiny GenAI span logger.

ponytail: JSON spans are enough for Loki/Grafana now; swap to OTel SDK when
we need collector export instead of stdout logs.
"""
from __future__ import annotations

import time
import uuid
from contextlib import asynccontextmanager
from typing import Any

import structlog

log = structlog.get_logger(__name__)


def _safe_log(event: str, **attrs: Any) -> None:
    try:
        log.info(event, **attrs)
    except Exception:
        # Instrumentation is observational; logging failure must not alter work.
        pass


class SpanTimer:
    """Fail-open structured span that can cross an async generator lifetime."""

    def __init__(self, name: str, kind: str, **attrs: Any):
        self.span_id = uuid.uuid4().hex[:16]
        self.name = name
        self.kind = kind
        self.attrs = attrs
        self.started_at = time.perf_counter()
        self._finished = False
        _safe_log(
            "genai_span_start",
            span_id=self.span_id,
            span_name=name,
            span_kind=kind,
            **attrs,
        )

    def finish(self, status: str = "ok", **attrs: Any) -> bool:
        if self._finished:
            return False
        self._finished = True
        duration_ms = int((time.perf_counter() - self.started_at) * 1000)
        _safe_log(
            "genai_span_end",
            span_id=self.span_id,
            span_name=self.name,
            span_kind=self.kind,
            duration_ms=duration_ms,
            status=status,
            **self.attrs,
            **attrs,
        )
        return True


@asynccontextmanager
async def genai_span(name: str, kind: str, **attrs: Any):
    timer = SpanTimer(name, kind, **attrs)
    try:
        yield timer.span_id
    except Exception as exc:
        timer.finish(
            status="error",
            error=str(exc)[:200],
        )
        raise
    else:
        timer.finish()
