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


@asynccontextmanager
async def genai_span(name: str, kind: str, **attrs: Any):
    span_id = uuid.uuid4().hex[:16]
    start = time.perf_counter()
    log.info("genai_span_start", span_id=span_id, span_name=name, span_kind=kind, **attrs)
    try:
        yield span_id
    except Exception as exc:
        duration_ms = int((time.perf_counter() - start) * 1000)
        log.info(
            "genai_span_end",
            span_id=span_id,
            span_name=name,
            span_kind=kind,
            duration_ms=duration_ms,
            status="error",
            error=str(exc)[:200],
            **attrs,
        )
        raise
    else:
        duration_ms = int((time.perf_counter() - start) * 1000)
        log.info(
            "genai_span_end",
            span_id=span_id,
            span_name=name,
            span_kind=kind,
            duration_ms=duration_ms,
            status="ok",
            **attrs,
        )
