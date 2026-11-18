# Android ↔ desktop UDP / QUIC verification

Independent diagnostic module. It does not modify or replace the production
Agent, Android application, pairing data, or RM server protocol.

The Android package is `com.codexrm.p2pprobe`. Its small Java activity loads the
same Go core used by the desktop executable through a JNI shared library. This
PoC uses direct NDK/JNI packaging; it does not yet define the production AAR API.

## What a successful round proves

1. New UDP sockets collect STUN mappings. The phone uses the cellular default
   network and refuses to start on Wi-Fi or a VPN.
2. The desktop keeps one source socket. It first checks signaled candidates,
   then a bounded neighboring-port window, then random distinct ports. The
   phone creates up to 256 sockets, aimed at the desktop's fixed public endpoint.
3. HMAC-protected selection chooses one phone socket. All other phone sockets
   close. The selected pair of sockets is handed to quic-go without rebinding.
4. QUIC uses TLS 1.3, mutual certificates, and exact SHA-256 certificate pins
   exchanged over the signaling channel. A 32 KiB randomized upload is checked
   with SHA-256, then a reverse RPC is completed. Both sides acknowledge RPC
   completion before closing the QUIC connection.

All payload traffic uses IPv4 UDP. There is no TURN or business relay in this
tool. USB carries only signaling; it does **not** carry the authenticated probes,
QUIC connection, random payload, or RPC responses. This isolates the NAT/data
path experiment from an eventual RM signaling implementation.

## Build

Run from this module directory:

```powershell
go test -race ./...
go vet ./...
go build -o ../../.work/p2p-20261118/probe.exe ./cmd/probe
.\scripts\build-android.ps1 -Sdk E:/app/android_studio_sdk -Jdk E:/app/android-studio/jbr -Output E:/code/Go/CodexRM/.work/p2p-20261118/android-build
```

The Android script uses NDK `28.2.13676358`, API 28, Build Tools 36, and an
arm64-v8a library linked with 16 KiB ELF page alignment. The script restores its
Go cross-compilation environment variables after building. APK signing uses
the local Android debug certificate. It installs alongside the production app.

## Signaling and execution

Launch the desktop coordinator with explicit STUN servers reachable by each
network. To classify address-dependent mappings, use at least two distinct
public server IPs. To assess alternate-source-port filtering, use a responder
that actually honors STUN CHANGE-PORT. This script's custom responder supports
alternate ports, not a complete RFC 5780 service or CHANGE-IP.

```powershell
probe.exe -rounds 200 -sockets 256 -ports 2048 -prediction 64 -window 20 `
  -stun 'STUN_IP:3478,STUN_IP:3479,162.159.207.0:3478,162.159.207.1:3478' `
  -phone-stun '162.159.207.0:3478,162.159.207.1:3478' -out PRIVATE_OUTPUT_DIRECTORY
adb install -r p2p-probe.apk
adb reverse tcp:18879 tcp:18879
adb push PRIVATE_OUTPUT_DIRECTORY/phone-config.json /data/local/tmp/codexrm-p2p-config.json
adb shell run-as com.codexrm.p2pprobe mkdir -p files
adb shell run-as com.codexrm.p2pprobe cp /data/local/tmp/codexrm-p2p-config.json files/config.json
adb shell am start -W -n com.codexrm.p2pprobe/.MainActivity
```

The coordinator binds **only to desktop loopback**, creates a random per-run
secret, and starts when the phone calls `/start`. Keep `phone-config.json`
private. `rounds.jsonl` contains two records per round. Count a round as successful
only if **both roles** report `success`, `udpHit`, `quic`, and `rpc`. A round is
not two independent successes. Never average incomplete or failed records away.

The 20-second deadline covers socket creation, diagnosis, signaling waits,
probing, TLS, and RPC. `punchMs` is a separate phase metric. Socket creation on
both sides restarts every round; this does not reset the carrier's overall
port block, public IP, or network conditions between rounds.

After the phone completes, collect its private result file without PowerShell
text transcoding and verify it exactly matches the coordinator's phone rows:

```powershell
python scripts/collect.py PRIVATE_OUTPUT_DIRECTORY
python scripts/analyze.py PRIVATE_OUTPUT_DIRECTORY
```

The analyzer leaves missing and failed rounds in the planned denominator and
marks an incomplete batch as incomplete. It publishes aggregate statistics and
does not publish addresses or the secret. The nominal Wilson interval assumes
independent trials; consecutive cellular trials need more cautious interpretation.

Probe send errors are counted and the first concrete error is retained in
current builds. The frozen initial 200-round experiment predates that telemetry
addition; it cannot retrospectively identify the send error in its timeout row.

## Limits of the experiment

- Not integrated with CodexRM's real RPC dispatcher or RM signaling yet.
- The Android UI checks network capabilities at run start. External network
  changes during a run need separate review of records and an end-state check.
- Prediction results do not prove random-only birthday traversal works. Use
  `-prediction 0` as a separate control and report `hitPhase`.
- `Sockets` is the number created, not the number of carrier mappings proved
  active. Early successful rounds stop before all sockets necessarily send.
- The phone's first socket is used for STUN; the selected socket may differ.
  Diagnose mapping behavior from the **same socket's** STUN observations; do not
  compare different phone sockets and call that evidence of Symmetric NAT.
- Sampling one device in one network epoch does not establish a rate across
  all carriers, APNs, locations, or idle periods. Wilson intervals do not remove
  correlation between adjacent trials.
- A STUN timeout is not by itself proof that all UDP traffic is blocked.
- This is a bounded, paired-peer engineering probe, not a public traversal
  service. Temporary responder ports/firewall rules must be removed after use.

Raw evidence and secrets belong under `.work/`, which is ignored by Git.
Publish only anonymized aggregates and explicit limitations.
