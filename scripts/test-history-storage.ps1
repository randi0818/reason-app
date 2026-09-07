param([string]$SqlitePath = 'sqlite3')

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$schemaRoot = Join-Path $repoRoot 'app/schemas/me.excuse.app.data.db.AppDatabase'
$sourceRoot = Join-Path $repoRoot 'app/src/main/java/me/excuse/app/data/db'

function Read-Queries([string]$file) {
    $source = Get-Content (Join-Path $sourceRoot $file) -Raw
    $queries = @{}
    $pattern = '@Query\((?:"""(?<multi>[\s\S]*?)"""|"(?<single>(?:\\.|[^"\\])*)")\)\s*(?:suspend\s+)?fun\s+(?<name>\w+)'
    foreach ($match in [regex]::Matches($source, $pattern)) {
        $queries[$match.Groups['name'].Value] = $match.Groups['multi'].Value + $match.Groups['single'].Value
    }
    return $queries
}

function Read-Migration([int]$from, [int]$to) {
    $source = Get-Content (Join-Path $sourceRoot 'AppDatabase.kt') -Raw
    $pattern = "val MIGRATION_${from}_${to} = [\s\S]*?(?=\r?\n        (?:val MIGRATION_|fun get))"
    $body = [regex]::Match($source, $pattern).Value
    $calls = [regex]::Matches($body, 'db\.execSQL\(\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+)\)')
    if ($calls.Count -eq 0) { throw "Missing migration $from -> $to" }
    foreach ($call in $calls) {
        $parts = [regex]::Matches($call.Groups[1].Value, '"(?:\\.|[^"\\])*"')
        (($parts | ForEach-Object { ConvertFrom-Json $_.Value }) -join '') + ';'
    }
}

function Run-Sql([string]$sql) {
    $result = $sql | & $SqlitePath ':memory:' 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}

$sessions = Read-Queries 'UsageSessionDao.kt'
$events = Read-Queries 'InterceptEventDao.kt'
$latest = Get-Content (Join-Path $schemaRoot '4.json') -Raw | ConvertFrom-Json
$indexAssertions = @()
foreach ($entity in $latest.database.entities) {
    foreach ($index in $entity.indices) {
        $columns = $index.columnNames -join ','
        $indexAssertions += "INSERT INTO assertions SELECT (SELECT group_concat(name, ',') FROM pragma_index_info('$($index.name)')) = '$columns';"
    }
}

# 每次只在 :memory: 中构造独立旧库；SQL 直接取自生产 DAO/Migration，不接触手机数据。
foreach ($version in 1..3) {
    $schema = Get-Content (Join-Path $schemaRoot "$version.json") -Raw | ConvertFrom-Json
    $create = ($schema.database.entities | ForEach-Object { $_.createSql.Replace('${TABLE_NAME}', $_.tableName) + ';' }) -join "`n"
    $migrations = ($version..3 | ForEach-Object { Read-Migration $_ ($_ + 1) }) -join "`n"
    $oldData = @()
    $oldDataAssertions = @()
    if ($version -ge 2) {
        $oldData += "INSERT INTO app_category VALUES ('pkg','SOCIAL');"
        $oldDataAssertions += "INSERT INTO assertions SELECT COUNT(*) = 1 FROM app_category WHERE packageName = 'pkg' AND category = 'SOCIAL';"
    }
    if ($version -eq 3) {
        $oldData += "INSERT INTO intercept_event VALUES (1,'pkg','示例','STARTED',100,1);"
        $oldDataAssertions += "INSERT INTO assertions SELECT COUNT(*) = 1 FROM intercept_event WHERE id = 1 AND outcome = 'STARTED' AND sessionId = 1 AND at = 100;"
    }
    $sql = @"
.bail on
$create
INSERT INTO usage_session VALUES
 (1,'pkg','示例','旧理由','旧理由',15,100,900,0),
 (2,'pkg','示例','当天','当天',15,1000,1500,0),
 (3,'pkg','示例','跨界','跨界',15,900,1100,0),
 (4,'pkg','示例','进行中','进行中',15,800,NULL,0),
 (5,'pkg','示例','边界','边界',15,900,1000,0),
 (6,'pkg','示例','校时','校时',15,1100,800,0),
 (7,'pkg','示例','负时间','负时间',15,-200,-100,0),
 (8,'pkg','示例','之后','之后',15,2000,3000,0),
 (9,'pkg','示例','延期','延期',20,500,600,1);
INSERT INTO monitored_app VALUES ('pkg','示例',1,100);
$($oldData -join "`n")
$migrations
CREATE TEMP TABLE assertions (ok INTEGER NOT NULL CHECK(ok = 1));
$($oldDataAssertions -join "`n")
INSERT INTO assertions SELECT COUNT(*) = 9 FROM usage_session;
INSERT INTO assertions SELECT reason = '旧理由' AND plannedMinutes = 15 AND endTime = 900 FROM usage_session WHERE id = 1;
INSERT INTO assertions SELECT endTime IS NULL FROM usage_session WHERE id = 4;
INSERT INTO assertions SELECT overran = 1 AND plannedMinutes = 20 FROM usage_session WHERE id = 9;
INSERT INTO assertions SELECT COUNT(*) = 4 FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%';
$($indexAssertions -join "`n")
INSERT OR IGNORE INTO app_category VALUES ('pkg','SOCIAL');
INSERT INTO intercept_event SELECT id,packageName,appName,'STARTED',startTime,id FROM usage_session WHERE id NOT IN (SELECT id FROM intercept_event);
INSERT INTO intercept_event VALUES (10,'pkg','示例','ABANDONED',999,NULL),(11,'pkg','示例','TIMEOUT',1000,NULL),(12,'pkg','示例','ABANDONED',1500,NULL),(13,'pkg','示例','TIMEOUT',-1,NULL);
.parameter init
.parameter set :before 1000
.parameter set :sinceMillis 1000
.parameter set :untilMillis 1600
INSERT INTO assertions SELECT ($($sessions.cleanupCount)) = 3;
INSERT INTO assertions SELECT ($($events.cleanupCount)) = 5;
INSERT INTO assertions SELECT (SELECT group_concat(id, ',') FROM (SELECT id FROM ($($sessions.sessionsBetween)) ORDER BY id)) = '2,3,4,5';
.parameter set :afterTime NULL
.parameter set :afterId 0
.parameter set :limit 3
INSERT INTO assertions SELECT (SELECT group_concat(id, ',') FROM ($($sessions.exportPage))) = '7,1,9';
.parameter set :afterTime 900
.parameter set :afterId 3
INSERT INTO assertions SELECT (SELECT group_concat(id, ',') FROM ($($sessions.exportPage))) = '5,2,6';
INSERT INTO assertions SELECT (SELECT group_concat(id, ',') FROM ($($events.exportPage))) = '5,10,2';
BEGIN;
$($events.deleteBefore);
$($sessions.deleteBefore);
INSERT INTO assertions SELECT (SELECT group_concat(id, ',') FROM (SELECT id FROM usage_session ORDER BY id)) = '2,3,4,5,6,8';
INSERT INTO assertions SELECT (SELECT group_concat(id, ',') FROM (SELECT id FROM intercept_event ORDER BY id)) = '2,3,4,5,6,8,11,12';
INSERT INTO assertions SELECT COUNT(*) = 0 FROM intercept_event e LEFT JOIN usage_session s ON e.sessionId = s.id WHERE e.sessionId IS NOT NULL AND s.id IS NULL;
INSERT INTO assertions SELECT COUNT(*) = 1 FROM monitored_app;
INSERT INTO assertions SELECT category = 'SOCIAL' FROM app_category WHERE packageName = 'pkg';
ROLLBACK;
INSERT INTO assertions SELECT COUNT(*) = 9 FROM usage_session;
INSERT INTO assertions SELECT COUNT(*) = 13 FROM intercept_event;
BEGIN;
$($events.deleteBefore);
$($sessions.deleteBefore);
COMMIT;
INSERT INTO assertions SELECT ($($sessions.cleanupCount)) = 0;
INSERT INTO assertions SELECT ($($events.cleanupCount)) = 0;
EXPLAIN QUERY PLAN $($sessions.sessionsBetween);
EXPLAIN QUERY PLAN $($sessions.exportPage);
EXPLAIN QUERY PLAN $($events.exportPage);
PRAGMA integrity_check;
"@
    $result = Run-Sql $sql
    $plan = $result -join "`n"
    foreach ($indexName in @('index_usage_session_endTime', 'index_usage_session_startTime', 'index_intercept_event_at')) {
        if ($plan -notmatch "SEARCH .* USING INDEX $indexName") { throw "Query is not seeking with $indexName : $plan" }
    }
    if ($result[-1] -ne 'ok') { throw "Integrity check failed: $plan" }
    Write-Output "PASS v$version -> v4: data preserved, indices, date boundaries, linked events, paging, rollback, integrity"
}
