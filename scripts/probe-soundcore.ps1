# Soundcore プロトコルを直接叩いて、切断・接続が実際に効くかを検証する。
# アプリの再取得結果ではなく、Windows 側の接続状態も独立に確認する。
param(
    [string]$Mac = "880E85018154",   # Space A40
    [string]$ModelCode = "3936"
)

$ErrorActionPreference = 'Stop'

$src = @'
using System;
using System.Runtime.InteropServices;
public class BtProbe {
  [StructLayout(LayoutKind.Sequential, Pack=1)]
  public struct SOCKADDR_BTH { public ushort addressFamily; public ulong btAddr; public Guid serviceClassId; public uint port; }
  [DllImport("ws2_32.dll")] public static extern IntPtr socket(int af, int type, int proto);
  [DllImport("ws2_32.dll")] public static extern int connect(IntPtr s, ref SOCKADDR_BTH name, int namelen);
  [DllImport("ws2_32.dll")] public static extern int send(IntPtr s, byte[] buf, int len, int flags);
  [DllImport("ws2_32.dll")] public static extern int recv(IntPtr s, byte[] buf, int len, int flags);
  [DllImport("ws2_32.dll")] public static extern int closesocket(IntPtr s);
  [DllImport("ws2_32.dll")] public static extern int WSAGetLastError();
  [DllImport("ws2_32.dll")] public static extern int WSAStartup(ushort v, byte[] data);
  [DllImport("ws2_32.dll")] public static extern int setsockopt(IntPtr s, int level, int optname, ref int optval, int optlen);

  [StructLayout(LayoutKind.Sequential)]
  public struct SYSTEMTIME { public ushort wYear,wMonth,wDayOfWeek,wDay,wHour,wMinute,wSecond,wMilliseconds; }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct BLUETOOTH_DEVICE_INFO {
    public int dwSize; public ulong address; public uint ulClassofDevice;
    public int fConnected; public int fRemembered; public int fAuthenticated;
    public SYSTEMTIME stLastSeen; public SYSTEMTIME stLastUsed;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=248)] public string szName;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct BLUETOOTH_DEVICE_SEARCH_PARAMS {
    public int dwSize; public int fReturnAuthenticated; public int fReturnRemembered;
    public int fReturnUnknown; public int fReturnConnected; public int fIssueInquiry;
    public byte cTimeoutMultiplier; public IntPtr hRadio;
  }
  [DllImport("C:\\Windows\\System32\\bthprops.cpl", CharSet=CharSet.Unicode)]
  public static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS p, ref BLUETOOTH_DEVICE_INFO i);
  [DllImport("C:\\Windows\\System32\\bthprops.cpl", CharSet=CharSet.Unicode)]
  public static extern bool BluetoothFindNextDevice(IntPtr h, ref BLUETOOTH_DEVICE_INFO i);
  [DllImport("C:\\Windows\\System32\\bthprops.cpl")]
  public static extern bool BluetoothFindDeviceClose(IntPtr h);
}
'@
Add-Type -TypeDefinition $src -ErrorAction Stop
$ws = New-Object byte[] 400
[BtProbe]::WSAStartup(0x0202, $ws) | Out-Null

function Build-Pkt([byte[]]$cmd, [byte[]]$body) {
    $len = 9 + $body.Length + 1
    $pkt = [System.Collections.Generic.List[byte]]::new()
    $pkt.AddRange([byte[]](0x08, 0xEE, 0x00, 0x00, 0x00))
    $pkt.AddRange($cmd)
    $pkt.Add([byte]($len -band 0xFF)); $pkt.Add([byte](($len -shr 8) -band 0xFF))
    if ($body.Length -gt 0) { $pkt.AddRange($body) }
    $sum = 0; foreach ($b in $pkt) { $sum = ($sum + $b) -band 0xFF }
    $pkt.Add([byte]$sum)
    return $pkt.ToArray()
}

function Invoke-Soundcore([byte[]]$cmd, [byte[]]$body, [int]$waitMs = 2500) {
    $s = [BtProbe]::socket(32, 1, 3)
    $timeout = $waitMs
    [void][BtProbe]::setsockopt($s, 0xFFFF, 0x1006, [ref]$timeout, 4)
    $sa = New-Object BtProbe+SOCKADDR_BTH
    $sa.addressFamily = 32
    $sa.btAddr = [Convert]::ToUInt64($Mac, 16)
    $sa.serviceClassId = [Guid]("0cf12d31-fac3-4553-bd80-d6832e7b" + $ModelCode)
    $sa.port = 0
    $r = [BtProbe]::connect($s, [ref]$sa, [System.Runtime.InteropServices.Marshal]::SizeOf($sa))
    if ($r -ne 0) {
        $err = [BtProbe]::WSAGetLastError()
        [BtProbe]::closesocket($s) | Out-Null
        return @{ Ok = $false; Error = "connect failed ($err)"; Frames = @() }
    }
    $pkt = Build-Pkt $cmd $body
    [BtProbe]::send($s, $pkt, $pkt.Length, 0) | Out-Null
    Start-Sleep -Milliseconds $waitMs
    $buf = New-Object byte[] 8192
    $n = [BtProbe]::recv($s, $buf, 8192, 0)
    [BtProbe]::closesocket($s) | Out-Null
    if ($n -le 0) { return @{ Ok = $true; Error = $null; Frames = @() } }
    return @{ Ok = $true; Error = $null; Frames = $buf[0..($n-1)] }
}

function Get-DeviceList {
    $res = Invoke-Soundcore ([byte[]](0x0B, 0x01)) ([byte[]]@())
    if (-not $res.Ok) { return @{ Error = $res.Error; Devices = @() } }
    $all = $res.Frames
    $devices = @()
    $i = 0
    while ($i -lt $all.Length - 4) {
        if ($all[$i] -eq 0x09 -and $all[$i+1] -eq 0xFF -and $all[$i+5] -eq 0x0B -and $all[$i+6] -eq 0x01) {
            $flen = $all[$i+7] -bor ($all[$i+8] -shl 8)
            if ($i + $flen -le $all.Length) {
                $body = $all[($i+9)..($i+$flen-2)]
                $pos = 2
                while ($pos -lt $body.Length) {
                    $l = $body[$pos]
                    if ($l -lt 8 -or ($pos + $l) -gt $body.Length) { break }
                    $conn = $body[$pos+1]
                    $mac = ($body[($pos+2)..($pos+7)] | ForEach-Object { $_.ToString('X2') }) -join ':'
                    $nb = $body[($pos+8)..($pos+$l-1)]
                    $e = [Array]::IndexOf($nb, [byte]0); if ($e -lt 0) { $e = $nb.Length }
                    $nm = [System.Text.Encoding]::UTF8.GetString($nb, 0, $e)
                    $devices += [pscustomobject]@{ Mac = $mac; Name = $nm; Connected = ($conn -eq 1) }
                    $pos += $l
                }
                $i += $flen
                continue
            }
        }
        $i++
    }
    return @{ Error = $null; Devices = $devices }
}

function Get-WindowsState {
    $p = New-Object BtProbe+BLUETOOTH_DEVICE_SEARCH_PARAMS
    $p.dwSize = [System.Runtime.InteropServices.Marshal]::SizeOf($p)
    $p.fReturnAuthenticated = 1; $p.fReturnRemembered = 1; $p.fReturnConnected = 1
    $i = New-Object BtProbe+BLUETOOTH_DEVICE_INFO
    $i.dwSize = [System.Runtime.InteropServices.Marshal]::SizeOf($i)
    $h = [BtProbe]::BluetoothFindFirstDevice([ref]$p, [ref]$i)
    $out = @()
    if ($h -ne [IntPtr]::Zero) {
        do {
            if ($i.szName -match 'soundcore|Space A40') {
                $out += [pscustomobject]@{ Name = $i.szName; Connected = ($i.fConnected -ne 0) }
            }
        } while ([BtProbe]::BluetoothFindNextDevice($h, [ref]$i))
        [BtProbe]::BluetoothFindDeviceClose($h) | Out-Null
    }
    return $out
}

function Show-State([string]$label) {
    Write-Host ""
    Write-Host "=== $label ===" -ForegroundColor Cyan
    $list = Get-DeviceList
    if ($list.Error) { Write-Host "  一覧取得失敗: $($list.Error)" -ForegroundColor Red; return }
    Write-Host "  [イヤホンが報告する接続先]"
    foreach ($d in $list.Devices) {
        $mark = if ($d.Connected) { "接続中" } else { "切断中" }
        Write-Host ("    {0,-24} {1}" -f $d.Name, $mark)
    }
    Write-Host "  [Windows が認識している接続状態]"
    foreach ($w in Get-WindowsState) {
        $mark = if ($w.Connected) { "接続中" } else { "切断中" }
        Write-Host ("    {0,-24} {1}" -f $w.Name, $mark)
    }
}

Write-Host "対象: MAC=$Mac modelCode=$ModelCode"
Show-State "初期状態"
