import re
import pathlib
import shutil

root = pathlib.Path(__file__).resolve().parents[1]
H2_MAPPER_NAMES = {
    "LogAnalysisTaskMapper.xml",
    "LogAnalysisDetailMapper.xml",
    "LogAlarmMapper.xml",
}


def h2ify_mapper_sql(content: str) -> str:
    content = re.sub(
        r"DATE_SUB\s*\(\s*NOW\s*\(\s*\)\s*,\s*INTERVAL\s+#\{(\w+)\}\s+(\w+)\s*\)",
        lambda m: f"DATEADD('{m.group(2).upper()}', -#{{{m.group(1)}}}, CURRENT_TIMESTAMP)",
        content,
        flags=re.IGNORECASE,
    )
    content = re.sub(
        r"\bdate\s*\(\s*create_time\s*\)",
        "CAST(create_time AS DATE)",
        content,
        flags=re.IGNORECASE,
    )
    return content


def generate_schema() -> None:
    src = (root / "src/main/resources/schema.sql").read_text(encoding="utf-8")
    src = re.sub(r" ENGINE=InnoDB[^;\n]*", "", src)
    src = re.sub(r" COMMENT '[^']*'", "", src)
    src = re.sub(r"\s+ON UPDATE CURRENT_TIMESTAMP", "", src)
    src = re.sub(r",?\s*KEY `idx_[^`]+` \([^)]+\)", "", src)

    extra = """

CREATE TABLE IF NOT EXISTS user_api_key (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  key_name varchar(128) NOT NULL,
  key_prefix varchar(32) NOT NULL,
  key_hash varchar(255) NOT NULL,
  scope_bundle varchar(255) DEFAULT NULL,
  status varchar(32) NOT NULL,
  last_used_at datetime DEFAULT NULL,
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime DEFAULT CURRENT_TIMESTAMP,
  revoked_at datetime DEFAULT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS user_profile_preference (
  user_id int NOT NULL,
  email_enabled tinyint DEFAULT 1,
  sms_enabled tinyint DEFAULT 0,
  task_alerts tinyint DEFAULT 1,
  update_time datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id)
);
"""
    out = root / "src/test/resources/schema-test.sql"
    out.write_text(src + extra, encoding="utf-8")
    print("written", out)


def generate_h2_mappers() -> None:
    src_dir = root / "src/main/resources/mapper"
    out_dir = root / "src/test/resources/mapper-h2"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    for path in sorted(src_dir.glob("*.xml")):
        content = path.read_text(encoding="utf-8")
        if path.name in H2_MAPPER_NAMES:
            content = h2ify_mapper_sql(content)
        (out_dir / path.name).write_text(content, encoding="utf-8")
    print("written", out_dir, f"({len(list(out_dir.glob('*.xml')))} files)")


if __name__ == "__main__":
    generate_schema()
    generate_h2_mappers()
