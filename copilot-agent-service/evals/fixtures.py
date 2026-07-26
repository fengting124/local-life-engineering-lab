"""Database-backed fixture references for repeatable Agent evaluations."""
from __future__ import annotations

import re
from dataclasses import fields, replace
from typing import Any, Iterable

from evals.eval_cases import EvalCase


FIXTURE_PATTERN = re.compile(r"\{\{fixture\.([a-zA-Z0-9_.-]+)\}\}")


class FixtureCatalog:
    def __init__(self, values: dict[str, Any]):
        self.values = dict(values)

    def has(self, name: str) -> bool:
        return name in self.values and self.values[name] is not None

    def get(self, name: str) -> Any:
        if not self.has(name):
            raise KeyError(name)
        return self.values[name]


def fixture_references(value: Any) -> set[str]:
    if isinstance(value, str):
        return set(FIXTURE_PATTERN.findall(value))
    if isinstance(value, dict):
        refs: set[str] = set()
        for key, item in value.items():
            refs.update(fixture_references(key))
            refs.update(fixture_references(item))
        return refs
    if isinstance(value, (list, tuple, set)):
        refs: set[str] = set()
        for item in value:
            refs.update(fixture_references(item))
        return refs
    return set()


def case_fixture_references(case: EvalCase) -> set[str]:
    refs: set[str] = set()
    for item in fields(case):
        refs.update(fixture_references(getattr(case, item.name)))
    return refs


def resolve_cases(cases: Iterable[EvalCase], catalog: FixtureCatalog) -> list[EvalCase]:
    return [_resolve_case(case, catalog) for case in cases]


def _resolve_case(case: EvalCase, catalog: FixtureCatalog) -> EvalCase:
    resolved = {
        item.name: _resolve_value(getattr(case, item.name), catalog)
        for item in fields(case)
    }
    return replace(case, **resolved)


def _resolve_value(value: Any, catalog: FixtureCatalog) -> Any:
    if isinstance(value, str):
        match = FIXTURE_PATTERN.fullmatch(value)
        if match:
            return catalog.get(match.group(1))
        return FIXTURE_PATTERN.sub(
            lambda found: str(catalog.get(found.group(1))),
            value,
        )
    if isinstance(value, dict):
        return {
            _resolve_value(key, catalog): _resolve_value(item, catalog)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_resolve_value(item, catalog) for item in value]
    if isinstance(value, tuple):
        return tuple(_resolve_value(item, catalog) for item in value)
    return value
