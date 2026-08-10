from pathlib import Path


MAIN = Path(__file__).parents[1] / "main.py"


def test_agent_bootstrap_includes_typed_checkpoint_tables():
    source = MAIN.read_text(encoding="utf-8")

    assert "CREATE TABLE IF NOT EXISTS `langgraph_checkpoint_v2`" in source
    assert "CREATE TABLE IF NOT EXISTS `langgraph_checkpoint_write_v2`" in source
    assert "`checkpoint_ns` VARCHAR(255)" in source
    assert "`state_blob` LONGBLOB" in source
    assert "`value_blob` LONGBLOB" in source
