param(
    [string]$Path = (Join-Path $PSScriptRoot '..\.env')
)

$resolvedPath = Resolve-Path -LiteralPath $Path -ErrorAction Stop
$loadedNames = [System.Collections.Generic.List[string]]::new()

foreach ($line in Get-Content -LiteralPath $resolvedPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
        continue
    }

    $separator = $trimmed.IndexOf('=')
    if ($separator -le 0) {
        throw "Invalid environment entry in $resolvedPath"
    }

    $name = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    $loadedNames.Add($name)
}

Write-Host "Loaded $($loadedNames.Count) environment variables into the current process."
