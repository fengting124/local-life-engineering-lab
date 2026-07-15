from main import parse_cors_allowed_origins


def test_parse_cors_allowed_origins_ignores_empty_items():
    origins = parse_cors_allowed_origins(" http://localhost:5173, ,http://127.0.0.1:3000/ ")

    assert origins == ["http://localhost:5173", "http://127.0.0.1:3000"]


def test_parse_cors_allowed_origins_keeps_explicit_wildcard():
    assert parse_cors_allowed_origins("*") == ["*"]

