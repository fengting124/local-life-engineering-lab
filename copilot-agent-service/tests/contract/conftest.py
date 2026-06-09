"""
契约测试专用 conftest：每次会话开始前清空 pacts/ 目录，
保证生成的 pact 文件是本次运行的确定性产物（pact-python 的 write_file 会向已存在文件
累加 interaction，不清理会在反复运行后产生重复条目）。
"""
import glob
import os

import pytest

PACT_DIR = os.path.join(os.path.dirname(__file__), "pacts")


@pytest.fixture(scope="session", autouse=True)
def _clean_pacts_dir():
    os.makedirs(PACT_DIR, exist_ok=True)
    for f in glob.glob(os.path.join(PACT_DIR, "*.json")):
        os.remove(f)
    yield
