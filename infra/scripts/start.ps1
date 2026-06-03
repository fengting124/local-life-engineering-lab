# ================================================================
# start.ps1 — Windows PowerShell 版一键启动脚本
# ================================================================
param(
    [switch]$SkipBuild  # 跳过重新构建镜像
)

$ErrorActionPreference = "Stop"
$InfraDir  = Split-Path $PSScriptRoot -Parent
$ProjectRoot = Split-Path $InfraDir -Parent

function Write-Step { param($msg) Write-Host "ℹ  $msg" -ForegroundColor Cyan }
function Write-OK   { param($msg) Write-Host "✅ $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "⚠️  $msg" -ForegroundColor Yellow }
function Write-Fail { param($msg) Write-Host "❌ $msg" -ForegroundColor Red; exit 1 }

Write-Host ""
Write-Host "================================================" -ForegroundColor Bold
Write-Host "   LocalLife × Copilot 全栈启动 (Windows)"    -ForegroundColor Bold
Write-Host "================================================" -ForegroundColor Bold
Write-Host ""

# ── 检查 .env ──
$EnvFile = Join-Path $InfraDir ".env"
if (-not (Test-Path $EnvFile)) {
    Copy-Item (Join-Path $InfraDir ".env.example") $EnvFile
    Write-Warn "已创建 infra/.env，请填写 ANTHROPIC_API_KEY 后重新运行"
    exit 1
}

# ── 读取 MYSQL_ROOT_PASSWORD ──
$MysqlPwd = (Get-Content $EnvFile | Where-Object { $_ -match "^MYSQL_ROOT_PASSWORD=" } |
    ForEach-Object { $_.Split("=",2)[1] }) ?? "123456"

Set-Location $InfraDir

# ── 启动中间件 ──
Write-Step "启动基础中间件（MySQL / Redis）..."
docker compose -f docker-compose.dev.yml up -d mysql redis
Write-OK "MySQL + Redis 启动中"

# ── 等待 MySQL ──
Write-Step "等待 MySQL 就绪（最多 60s）..."
$waited = 0
while ($waited -lt 60) {
    $result = docker exec local-life-mysql mysql -uroot -p"$MysqlPwd" -e "SELECT 1" 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep 2; $waited += 2; Write-Host "." -NoNewline
}
Write-Host ""
if ($waited -ge 60) { Write-Fail "MySQL 未就绪" }
Write-OK "MySQL 已就绪"

# ── 数据库迁移 ──
Write-Step "执行数据库迁移..."
docker exec local-life-mysql mysql -uroot -p"$MysqlPwd" -e `
    "CREATE DATABASE IF NOT EXISTS local_life CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>$null

# 创建迁移记录表
docker exec local-life-mysql mysql -uroot -p"$MysqlPwd" local_life -e @"
CREATE TABLE IF NOT EXISTS schema_migrations (
  version VARCHAR(20) NOT NULL PRIMARY KEY,
  description VARCHAR(200) NOT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"@ 2>$null

# 执行迁移文件
$migrationDirs = @(
    (Join-Path $ProjectRoot "local-life-server\src\main\resources\db\migration"),
    (Join-Path $ProjectRoot "local-life-copilot\src\main\resources\db\migration")
)
foreach ($dir in $migrationDirs) {
    if (Test-Path $dir) {
        Get-ChildItem $dir -Filter "V*.sql" | Sort-Object Name | ForEach-Object {
            $version = $_.Name -replace '^(V\d+)__.*', '$1'
            $already = docker exec local-life-mysql mysql -uroot -p"$MysqlPwd" local_life -sN `
                -e "SELECT COUNT(*) FROM schema_migrations WHERE version='$version'" 2>$null
            if ($already -eq "1") {
                Write-Host "   ⏭  $($_.Name) (已执行)" -ForegroundColor Gray
            } else {
                Get-Content $_.FullName | docker exec -i local-life-mysql mysql -uroot -p"$MysqlPwd" local_life 2>$null
                docker exec local-life-mysql mysql -uroot -p"$MysqlPwd" local_life -e `
                    "INSERT IGNORE INTO schema_migrations(version,description) VALUES('$version','$($_.Name)')" 2>$null
                Write-Host "   ✅ $($_.Name)" -ForegroundColor Green
            }
        }
    }
}
Write-OK "数据库迁移完成"

# ── 构建并启动应用 ──
if ($SkipBuild) {
    Write-Step "启动应用服务（跳过构建）..."
    docker compose -f docker-compose.dev.yml --profile app up -d
} else {
    Write-Step "构建应用镜像（首次约需 3-5 分钟）..."
    docker compose -f docker-compose.dev.yml --profile app up -d --build
}

# ── 等待服务就绪 ──
Write-Step "等待服务启动..."
$services = @(
    @{ Name="LocalLife Server"; Url="http://localhost:8080/actuator/health" },
    @{ Name="MCP Server";       Url="http://localhost:8081/actuator/health" },
    @{ Name="Agent Service";    Url="http://localhost:8000/health" }
)
foreach ($svc in $services) {
    Write-Host "   等待 $($svc.Name)..." -NoNewline
    $w = 0
    while ($w -lt 120) {
        try { $r = Invoke-WebRequest $svc.Url -TimeoutSec 2 -UseBasicParsing 2>$null; if ($r.StatusCode -eq 200) { break } } catch {}
        Start-Sleep 3; $w += 3; Write-Host "." -NoNewline
    }
    Write-Host " ✅"
}

# ── 完成 ──
Write-Host ""
Write-Host "================================================" -ForegroundColor Green
Write-Host "   🚀 LocalLife Copilot 已成功启动！" -ForegroundColor Green
Write-Host "================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  📱 Chat UI     http://localhost:8000/" -ForegroundColor Cyan
Write-Host "  ⚠️  审批工作台  http://localhost:8000/approval" -ForegroundColor Cyan
Write-Host "  📖 API 文档    http://localhost:8000/docs" -ForegroundColor Cyan
Write-Host "  📊 Grafana     http://localhost:3000  (admin/admin)" -ForegroundColor Cyan
Write-Host ""
