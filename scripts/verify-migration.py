"""Execute each actual migration against SQLite, checking exported schemas and old data."""
from pathlib import Path
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / 'app/schemas/com.shadowreader.app.data.ArticleDatabase'
v1 = json.loads((SCHEMAS / '1.json').read_text())['database']
v2 = json.loads((SCHEMAS / '2.json').read_text())['database']
v3 = json.loads((SCHEMAS / '3.json').read_text())['database']
source = (ROOT / 'app/src/main/java/com/shadowreader/app/data/ArticleDatabase.kt').read_text(encoding='utf-8')
def migration(start, end):
    block = source.split(f'val MIGRATION_{start}_{end} =', 1)[1].split('\n        }', 1)[0]
    return re.findall(r'db\.execSQL\("([^"]+)"\)', block)
sql = migration(1, 2)
assert len(sql) == 5

def create_schema(db, schema):
    db.execute('PRAGMA foreign_keys = ON')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

db = sqlite3.connect(':memory:')
create_schema(db, v1)
db.execute("INSERT INTO articles VALUES ('old', 'preserved title', 'https://example.com', 'original body', 2, 1234, 1, 2)")
db.execute("INSERT INTO sentences VALUES ('old', 0, 'First sentence.')")
db.execute("INSERT INTO sentences VALUES ('old', 1, 'Second sentence.')")
before = db.execute('SELECT * FROM articles').fetchall()
for statement in sql:
    db.execute(statement)
expected = sqlite3.connect(':memory:')
create_schema(expected, v2)
for entity in v2['entities']:
    table = entity['tableName']
    for pragma in ['table_info', 'foreign_key_list', 'index_list']:
        actual = sorted(db.execute(f'PRAGMA {pragma}({table})').fetchall())
        want = sorted(expected.execute(f'PRAGMA {pragma}({table})').fetchall())
        assert actual == want, (table, pragma, actual, want)
    for index in entity.get('indices', []):
        name = index['name']
        assert db.execute(f'PRAGMA index_info({name})').fetchall() == expected.execute(f'PRAGMA index_info({name})').fetchall()
assert before == db.execute('SELECT * FROM articles').fetchall(), 'Old progress or content changed'
assert db.execute('SELECT COUNT(*) FROM sentences').fetchone()[0] == 2
assert db.execute('SELECT COUNT(*) FROM attempts').fetchone()[0] == 0, 'Old progress was converted into ASR results'
db.execute("INSERT INTO training_sessions VALUES ('session', 1000, NULL, 0)")
db.execute("INSERT INTO attempts VALUES ('attempt', 'session', 'old', 1, 2000, 'text', '[]', 'CONSISTENT', 0.0)")
db.execute("INSERT INTO sentence_reviews VALUES ('old', 1, 1, 2, 0, 100)")
for statement in migration(2, 3):
    db.execute(statement)
expected3 = sqlite3.connect(':memory:')
create_schema(expected3, v3)
for entity in v3['entities']:
    table = entity['tableName']
    for pragma in ['table_info', 'foreign_key_list', 'index_list']:
        assert sorted(db.execute(f'PRAGMA {pragma}({table})').fetchall()) == sorted(expected3.execute(f'PRAGMA {pragma}({table})').fetchall()), (table, pragma)
assert db.execute("SELECT recognized, result, pronunciationJson, feedbackMode FROM attempts WHERE id='attempt'").fetchone() == ('text','CONSISTENT',None,'LEGACY')
assert before == db.execute('SELECT * FROM articles').fetchall()
db.execute("UPDATE attempts SET pronunciationJson='{}', feedbackMode='PRONUNCIATION' WHERE id='attempt'")
try:
    db.execute("INSERT INTO attempts (id,sessionId,articleId,position,createdAt,recognized,differences,result,errorRatio,feedbackMode) VALUES ('bad', 'session', 'missing', 0, 2000, '', '', 'UNEVALUATED', 0.0,'LEGACY')")
    raise AssertionError('Orphan attempt was accepted')
except sqlite3.IntegrityError:
    pass
db.execute("DELETE FROM articles WHERE id = 'old'")
for table in ['sentences', 'attempts', 'sentence_reviews']:
    assert db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0] == 0, 'Cascade failed: ' + table
assert db.execute('PRAGMA foreign_key_check').fetchall() == []
print('PASS: actual 1->2->3 SQL matches Room schemas; old content, ASR and progress preserved; pronunciation columns, foreign keys and cascades verified.')
