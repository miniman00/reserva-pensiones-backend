param(
    [string]$BaseUrl = $(if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" })
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')

function Test-Endpoint {
    param(
        [string]$Name,
        [string]$Path,
        [int]$ExpectedStatus
    )

    $headersFile = [System.IO.Path]::GetTempFileName()
    $bodyFile = [System.IO.Path]::GetTempFileName()
    try {
        $statusText = & curl.exe -sS -D $headersFile -o $bodyFile -w "%{http_code}" "$BaseUrl$Path"
        if ($LASTEXITCODE -ne 0) {
            throw "curl falló para $Path"
        }
        $status = [int]$statusText
        if ($status -ne $ExpectedStatus) {
            $body = Get-Content $bodyFile -Raw
            throw "[$Name] HTTP $status; esperado $ExpectedStatus. Body: $body"
        }

        $headers = Get-Content $headersFile -Raw
        if ($headers -notmatch '(?im)^X-Request-Id:') {
            throw "[$Name] falta X-Request-Id"
        }
        Write-Host "[OK]   $Name -> HTTP $status"
    }
    finally {
        Remove-Item $headersFile, $bodyFile -Force -ErrorAction SilentlyContinue
    }
}

Test-Endpoint "Liveness" "/actuator/health/liveness" 200
Test-Endpoint "Readiness" "/actuator/health/readiness" 200
Test-Endpoint "Búsqueda pública" "/api/public/pensions?page=0&size=1" 200
Test-Endpoint "Sitemap" "/sitemap.xml" 200
Test-Endpoint "Robots" "/robots.txt" 200
Test-Endpoint "Detalle público inexistente" "/api/public/pensions/9223372036854775807" 404
Test-Endpoint "API privada anónima" "/api/me" 401

Write-Host "Smoke API completado correctamente contra $BaseUrl"
