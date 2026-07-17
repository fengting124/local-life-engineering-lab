$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InfraDir = Resolve-Path (Join-Path $ScriptDir "..")
$BaseCompose = Join-Path $InfraDir "docker-compose.dev.yml"
$LiteCompose = Join-Path $InfraDir "docker-compose.lite.yml"

$composeArgs = @("compose", "-f", $BaseCompose, "-f", $LiteCompose)

& docker @composeArgs --profile app up -d
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start LocalLife Lite stack"
}

if ($env:WITH_RAG_MODELS -eq "true") {
    & docker @composeArgs up -d embedding-service reranker-service
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start RAG model services"
    }
}

$agentPort = if ($env:AGENT_PORT) { $env:AGENT_PORT } else { "8000" }
Write-Host ""
Write-Host "LocalLife Lite started."
Write-Host "Agent: http://localhost:$agentPort/"
Write-Host "Milvus Lite file is persisted in Docker volume agent_rag_data."
Write-Host "Set WITH_RAG_MODELS=true to start local embedding and reranker services."
