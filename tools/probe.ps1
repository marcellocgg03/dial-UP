# tools/probe.ps1 -- Windows-host SSDP/DIAL prober (matrix row 1 positive control, rows 5 and 6 timeline).
#
# Run from WSL via tools/probe.sh (WSL mirrored networking cannot receive SSDP replies), or directly:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File <winpath> -Target <phone-ip> -LoopSeconds 60 -Minutes 35
#
# Safety: this script only sends M-SEARCH datagrams and default HTTP GET requests (no explicit method
# parameter anywhere), so it cannot trigger a cast (a launch is a POST). One search per LoopSeconds.
#
# Output, one tab-separated line per probe:
#   ISO-timestamp  mcast=N  ucast=N  from=ip,ip  ports=p,p  desc=...  app=...
# mcast is the primary signal. ucast is diagnostic only: unicast to port 1900 reaches one of several
# listeners, so a missing unicast reply does not prove a broken responder.
param([int]$PortMin=3100,[int]$PortMax=3109,[string]$Target="",[int]$LoopSeconds=0,[int]$Minutes=1,
      [string]$ST="urn:dial-multiscreen-org:service:dial:1")

function Send-Search($u,$dest,$tag){
  $m="M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: $ST`r`nUSER-AGENT: t2u-probe/1.0 ($tag)`r`n`r`n"
  $b=[Text.Encoding]::ASCII.GetBytes($m); [void]$u.Send($b,$b.Length,$dest,1900) }

# Collect replies for $seconds. Only replies whose LOCATION port is inside PortMin..PortMax are kept
# (T-01-28: other devices on the LAN are ignored). Each reply is a record with Ip, Loc, Port, LocHost.
function Collect($u,$seconds){ $out=@(); $end=(Get-Date).AddSeconds($seconds)
  while((Get-Date) -lt $end){ try{ $ep=New-Object Net.IPEndPoint([Net.IPAddress]::Any,0); $d=$u.Receive([ref]$ep)
    $t=[Text.Encoding]::UTF8.GetString($d)
    $loc=($t -split "`r`n"|Where-Object{$_ -match '^(?i)location:'}|Select-Object -First 1) -replace '^(?i)location:\s*',''
    if($loc -match '^https?://([^:/]+):(\d+)/'){ $lh=$Matches[1]; $p=[int]$Matches[2]
      if($p -ge $PortMin -and $p -le $PortMax){ $out+=[pscustomobject]@{Ip=$ep.Address.ToString();Loc=$loc;Port=$p;LocHost=$lh} } }
  }catch{} }; $out }

function Probe-Once{ $ts=(Get-Date).ToString("o"); $u=New-Object Net.Sockets.UdpClient(0); $u.Client.ReceiveTimeout=500
  Send-Search $u "239.255.255.250" "mcast"; $mc=@(Collect $u 3)
  $uc=@(); if($Target){ Send-Search $u $Target "ucast"; $uc=@(Collect $u 2) }; $u.Close()
  $all=@($mc+$uc)
  # Skip LOCATIONs on 127.0.0.1: the spike's variant D advertises loopback, which from the laptop would
  # point at the laptop itself, not at the phone.
  $loc=($all|Where-Object{$_.LocHost -ne '127.0.0.1'}|Select-Object -First 1).Loc; $desc="-"; $app="-"
  if($loc){ try{ $r=Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 $loc; $au=$r.Headers['Application-URL']; $desc="200 AppURL=$au"
        if($au){ $a=Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "$au/YouTube"; $app="$($a.StatusCode)" } }catch{ $desc="ERR $($_.Exception.Message)" } }
  $ports=if($all.Count){ (($all|Select-Object -ExpandProperty Port -Unique|Sort-Object) -join ',') }else{ "-" }
  $from=if($all.Count){ (($all|Select-Object -ExpandProperty Ip -Unique) -join ',') }else{ "-" }
  "{0}`tmcast={1}`tucast={2}`tfrom={3}`tports={4}`tdesc={5}`tapp={6}" -f $ts,$mc.Count,$uc.Count,$from,$ports,$desc,$app }

$stop=(Get-Date).AddMinutes($Minutes)
do{ Probe-Once; if($LoopSeconds -gt 0){ Start-Sleep -Seconds $LoopSeconds } } while($LoopSeconds -gt 0 -and (Get-Date) -lt $stop)
