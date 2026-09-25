<#
.SYNOPSIS
    Sends a real CTRL+C console event to another process.

.DESCRIPTION
    Must run in its own PowerShell process: attaching to another console detaches the caller from its own,
    which would break the calling terminal. stop-server.ps1 invokes it that way.

    This is the only way to shut a console JVM down gracefully from a script. Stop-Process calls TerminateProcess,
    which kills the JVM without running its shutdown hooks — for the Aion game server that means no player data
    is saved.

.PARAMETER TargetPid
    Process id whose console should receive the event.
#>
param(
    [Parameter(Mandatory = $true)][int]$TargetPid
)

Add-Type -Namespace Win32 -Name Kernel -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool AttachConsole(uint dwProcessId);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool FreeConsole();
[DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(IntPtr handlerRoutine, bool add);
[DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint dwCtrlEvent, uint dwProcessGroupId);
'@

$CTRL_C_EVENT = 0

[Win32.Kernel]::FreeConsole() | Out-Null
if (-not [Win32.Kernel]::AttachConsole([uint32]$TargetPid)) {
    exit 1
}

# ignore the event in this process, otherwise it dies before the target handles it
[Win32.Kernel]::SetConsoleCtrlHandler([IntPtr]::Zero, $true) | Out-Null
$sent = [Win32.Kernel]::GenerateConsoleCtrlEvent($CTRL_C_EVENT, 0)
Start-Sleep -Milliseconds 500
[Win32.Kernel]::FreeConsole() | Out-Null

if ($sent) { exit 0 } else { exit 2 }
