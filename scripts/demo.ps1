[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username,
    [string]$SampleFile = "..\demo-data\employee-leave-policy.md",
    [string]$Question = "员工请假制度中，年假申请需要经过哪些步骤？",
    [ValidateRange(30, 1800)]
    [int]$IngestionTimeoutSeconds = 600
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "此演示脚本需要 PowerShell 7 或更高版本。"
}

$BaseUrl = $BaseUrl.TrimEnd("/")
$samplePath = if ([System.IO.Path]::IsPathRooted($SampleFile)) {
    $SampleFile
} else {
    Join-Path $PSScriptRoot $SampleFile
}
if (-not (Test-Path -LiteralPath $samplePath -PathType Leaf)) {
    throw "找不到示例文件：$samplePath"
}
$samplePath = (Resolve-Path -LiteralPath $samplePath).Path

if ([string]::IsNullOrWhiteSpace($Username)) {
    $Username = Read-Host "输入用于演示的用户名（不存在时会注册）"
}
$securePassword = Read-Host "输入密码（10-128 位；如账号不存在会注册）" -AsSecureString
$passwordPointer = [IntPtr]::Zero
try {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}
if ($password.Length -lt 10 -or $password.Length -gt 128) {
    throw "密码长度必须为 10 到 128 位。"
}

function Invoke-IntelHubApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [hashtable]$Headers = @{},
        [object]$Body,
        [hashtable]$Form,
        [int]$TimeoutSec = 60
    )
    $request = @{
        Uri         = "$BaseUrl/api$Path"
        Method      = $Method
        Headers     = $Headers
        TimeoutSec  = $TimeoutSec
        ErrorAction = "Stop"
    }
    if ($PSBoundParameters.ContainsKey("Body")) {
        $request.ContentType = "application/json; charset=utf-8"
        $request.Body = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
    }
    if ($PSBoundParameters.ContainsKey("Form")) {
        $request.Form = $Form
    }
    $response = Invoke-RestMethod @request
    if ($null -ne $response.PSObject.Properties["success"] -and -not $response.success) {
        throw "IntelHub API 返回失败：$($response.message)"
    }
    if ($null -ne $response.PSObject.Properties["data"]) {
        return ,$response.data
    }
    return $response
}

Write-Host "检查 IntelHub 服务：$BaseUrl"
try {
    $null = Invoke-WebRequest -Uri "$BaseUrl/api/health/ready" -TimeoutSec 10
} catch {
    throw "无法访问 readiness。请先启动 Compose 并确认 $BaseUrl/api/health/ready 可用。详情：$($_.Exception.Message)"
}

Write-Host "尝试注册账号 $Username ..."
try {
    $null = Invoke-IntelHubApi -Method POST -Path "/auth/register" -Body @{
        username = $Username
        password = $password
        displayName = "IntelHub 演示用户"
    }
    Write-Host "注册成功。"
} catch {
    $statusCode = 0
    try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }
    if ($statusCode -eq 409) {
        Write-Host "用户名已存在，继续尝试登录。"
    } else {
        throw "注册失败。请检查用户名格式、密码长度或服务状态。详情：$($_.Exception.Message)"
    }
}

$login = Invoke-IntelHubApi -Method POST -Path "/auth/login" -Body @{
    username = $Username
    password = $password
}
if ([string]::IsNullOrWhiteSpace($login.token)) {
    throw "登录响应中没有 token。"
}
$headers = @{ satoken = $login.token }
$me = Invoke-IntelHubApi -Method GET -Path "/auth/me" -Headers $headers
Write-Host "登录成功：$($me.displayName)（$($me.role)）"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$knowledgeBase = Invoke-IntelHubApi -Method POST -Path "/knowledge-bases" -Headers $headers -Body @{
    name = "员工制度演示库 $stamp"
    description = "由演示脚本创建，来源为虚构员工年假流程示例资料。"
}
$knowledgeBaseId = $knowledgeBase.id
Write-Host "已创建知识库：$($knowledgeBase.name)"

$fileItem = Get-Item -LiteralPath $samplePath
$document = Invoke-IntelHubApi -Method POST -Path "/knowledge-bases/$knowledgeBaseId/documents" -Headers $headers -Form @{
    file = $fileItem
}
$documentId = $document.id
Write-Host "已上传 $($document.filename)，当前状态：$($document.status)"

$deadline = (Get-Date).AddSeconds($IngestionTimeoutSeconds)
$ingested = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $documents = Invoke-IntelHubApi -Method GET -Path "/knowledge-bases/$knowledgeBaseId/documents" -Headers $headers
    $currentDocument = @($documents | Where-Object { $_.id -eq $documentId } | Select-Object -First 1)
    if ($currentDocument.Count -eq 0) {
        continue
    }
    $status = [string]$currentDocument[0].status
    Write-Host "入库状态：$status"
    if ($status -eq "COMPLETED") {
        $ingested = $true
        Write-Host "入库完成，分块数：$($currentDocument[0].chunkCount)"
        break
    }
    if ($status -eq "FAILED") {
        throw "入库失败：$($currentDocument[0].error)"
    }
}
if (-not $ingested) {
    throw "等待入库超过 $IngestionTimeoutSeconds 秒。请检查 RocketMQ 与后端日志。"
}

Write-Host "发送 POST SSE 问答：$Question"
$chatBody = @{
    knowledgeBaseId = $knowledgeBaseId
    question = $Question
}
$chatRequest = @{
    Uri         = "$BaseUrl/api/chat/stream"
    Method      = "POST"
    Headers     = $headers
    ContentType = "application/json; charset=utf-8"
    Body        = ConvertTo-Json -InputObject $chatBody -Compress
    TimeoutSec  = 330
    ErrorAction = "Stop"
}
$sse = Invoke-WebRequest @chatRequest
if ($sse.StatusCode -ne 200) {
    throw "问答接口返回 HTTP $($sse.StatusCode)。"
}

$conversationId = $null
$traceId = $null
$citationCount = 0
$answerTokenCount = 0
$eventName = "message"
$dataLines = [System.Collections.Generic.List[string]]::new()
$answerStarted = $false
foreach ($line in ($sse.Content -split "\r?\n")) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        if ($dataLines.Count -gt 0) {
            $eventData = [string]::Join([Environment]::NewLine, $dataLines)
            switch ($eventName) {
                "status" {
                    try {
                        $payload = $eventData | ConvertFrom-Json
                        Write-Host ([Environment]::NewLine + "[$($payload.stage)] $($payload.message)")
                    } catch { }
                }
                "token" {
                    try {
                        $payload = $eventData | ConvertFrom-Json
                        $tokenText = [string]$payload.text
                        if (-not [string]::IsNullOrWhiteSpace($tokenText)) {
                            if (-not $answerStarted) {
                                Write-Host ([Environment]::NewLine + "回答：") -NoNewline
                                $answerStarted = $true
                            }
                            $answerTokenCount++
                            Write-Host $tokenText -NoNewline
                        }
                    } catch { }
                }
                "citations" {
                    try {
                        $citations = @($eventData | ConvertFrom-Json)
                        $citationCount += $citations.Count
                        Write-Host ([Environment]::NewLine + "引用来源：")
                        foreach ($citation in $citations) {
                            Write-Host "  - $($citation.filename)（片段 $($citation.chunkIndex)）：$($citation.excerpt)"
                        }
                    } catch {
                        Write-Host ([Environment]::NewLine + "收到引用事件。")
                    }
                }
                "done" {
                    $payload = $eventData | ConvertFrom-Json
                    $conversationId = $payload.conversationId
                    $traceId = $payload.traceId
                    Write-Host ([Environment]::NewLine + "SSE 完成：model=$($payload.modelId)，trace=$traceId")
                }
                "error" {
                    $payload = $eventData | ConvertFrom-Json
                    throw "问答失败：$($payload.message)"
                }
                "clarification" {
                    $payload = $eventData | ConvertFrom-Json
                    Write-Host ([Environment]::NewLine + "系统请求澄清：$($payload.message)")
                    foreach ($option in $payload.options) {
                        Write-Host "  - $($option.name)"
                    }
                }
            }
        }
        $eventName = "message"
        $dataLines.Clear()
        continue
    }
    if ($line.StartsWith("event:")) {
        $eventName = $line.Substring(6).Trim()
    } elseif ($line.StartsWith("data:")) {
        $dataLines.Add($line.Substring(5).TrimStart())
    }
}
if ($answerStarted) { Write-Host "" }
if ([string]::IsNullOrWhiteSpace($traceId)) {
    throw "SSE 响应没有 done 事件。原始响应：$($sse.Content)"
}
if ($answerTokenCount -lt 1) {
    throw "SSE 已完成但没有收到非空回答 token。请检查模型路由和 SSE 响应。"
}
if ($citationCount -lt 1) {
    throw "问答已结束但没有收到引用。请检查文档索引和 Trace 检索结果。"
}

$traceRows = Invoke-IntelHubApi -Method GET -Path "/traces?limit=20" -Headers $headers
$trace = @($traceRows | Where-Object { $_.id -eq $traceId } | Select-Object -First 1)
if ($trace.Count -eq 0) {
    throw "SSE 已完成，但 Trace API 中暂时找不到 traceId=$traceId。"
}
if ($trace[0].status -ne "COMPLETED") {
    throw "Trace 状态不是 COMPLETED：$($trace[0].status)"
}
Write-Host "Trace 状态：$($trace[0].status)，耗时：$($trace[0].durationMs) ms"
Write-Host "检索记录：$($trace[0].retrieval)"
Write-Host ""
Write-Host "演示完成。知识库 ID：$knowledgeBaseId"
Write-Host "会话 ID：$conversationId"
Write-Host "Trace ID：$traceId"
Write-Host "这些数据会保留，便于在 Vue 页面查看。"
