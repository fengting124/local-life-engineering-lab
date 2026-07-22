import importlib.util
from pathlib import Path


def _load_module():
    path = Path(__file__).with_name("locustfile_locallife_server.py")
    spec = importlib.util.spec_from_file_location("locustfile_locallife_server", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class _Response:
    status_code = 200

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def json(self):
        return {"data": {"token": "test-token"}}

    def failure(self, message):
        raise AssertionError(message)


class _Client:
    def __init__(self):
        self.headers = {}
        self.posts = []

    def post(self, path, **kwargs):
        self.posts.append((path, kwargs))
        return _Response()


def test_mixed_user_uses_isolated_seeded_account_without_requesting_new_code():
    module = _load_module()
    user = object.__new__(module.LocalLifeUser)
    user.client = _Client()

    user.on_start()

    assert [path for path, _ in user.client.posts] == ["/api/v1/auth/login"]
    assert user.client.posts[0][1]["json"]["mobile"] == "13900001500"
    assert user.client.headers["X-Forwarded-For"].startswith("10.10.")
    assert user.token == "test-token"


def test_seckill_user_sets_distinct_forwarded_ip_before_login():
    module = _load_module()
    user = object.__new__(module.SeckillUser)
    user.client = _Client()

    user.on_start()

    assert user.client.posts[0][1]["json"]["mobile"] == "13900000000"
    assert user.client.headers["X-Forwarded-For"].startswith("10.20.")


def test_search_user_sets_isolated_forwarded_ip():
    module = _load_module()
    user = object.__new__(module.SearchUser)
    user.client = _Client()

    user.on_start()

    assert user.client.headers["X-Forwarded-For"].startswith("10.30.")
