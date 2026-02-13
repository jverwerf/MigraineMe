# Run from: C:\Users\verwe\Projects\MigraineMe

# 1. Create xml directory if it doesn't exist
$xmlDir = "app\src\main\res\xml"
if (!(Test-Path $xmlDir)) {
    New-Item -ItemType Directory -Path $xmlDir -Force
    Write-Host "Created $xmlDir"
}

# 2. Create file_paths.xml
$filePathsContent = @'
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="reports" path="reports/" />
</paths>
'@
Set-Content -Path "$xmlDir\file_paths.xml" -Value $filePathsContent -Encoding UTF8
Write-Host "Created $xmlDir\file_paths.xml"

# 3. Add FileProvider to AndroidManifest.xml
$manifestPath = "app\src\main\AndroidManifest.xml"
$manifest = Get-Content $manifestPath -Raw

if ($manifest -match "FileProvider") {
    Write-Host "FileProvider already exists in manifest - skipping"
} else {
    $providerBlock = @'

        <!-- FileProvider for sharing PDF reports -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
'@
    # Insert before </application>
    $manifest = $manifest -replace '</application>', "$providerBlock`n    </application>"
    Set-Content -Path $manifestPath -Value $manifest -Encoding UTF8
    Write-Host "Added FileProvider to $manifestPath"
}

Write-Host "Done!"
