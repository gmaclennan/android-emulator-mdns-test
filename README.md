# Android Emulator mDNS/NSD Testing on GitHub CI

This repository is a prototype for testing [NSD (Network Service Discovery)](https://developer.android.com/training/connect-devices-wirelessly/nsd) between two Android emulators running on GitHub Actions CI infrastructure.

## Background

[CoMapeo](https://github.com/digidem/comapeo-core-react-native) uses DNS-SD over mDNS to discover nearby devices via Android's NSD stack. The goal is to test this discovery mechanism in CI by running two emulators on the same host and having them discover each other.

The fundamental challenge: the Android emulator uses QEMU's SLIRP (user-mode) networking by default, which **does not support multicast**. Each emulator instance sits behind its own isolated virtual router in a `10.0.2/24` subnet. mDNS relies on multicast to `224.0.0.251:5353`, and SLIRP simply drops these packets.

## Solution: QEMU Socket Multicast

The recommended approach adds a **second NIC** to each emulator using QEMU's socket multicast backend, which creates a shared L2 network segment between emulators without any host network setup or root privileges:

```bash
emulator @avd1 -no-window -gpu swiftshader_indirect -no-snapshot \
  -qemu \
  -device virtio-net-pci,netdev=sharedlan,mac=52:54:00:12:34:56 \
  -netdev socket,id=sharedlan,mcast=230.0.0.1:1234

emulator @avd2 -no-window -gpu swiftshader_indirect -no-snapshot \
  -qemu \
  -device virtio-net-pci,netdev=sharedlan,mac=52:54:00:12:34:57 \
  -netdev socket,id=sharedlan,mcast=230.0.0.1:1234
```

The QEMU instances exchange raw Ethernet frames over a host UDP multicast group, creating a shared L2 segment.

After boot, configure the second NIC with static IPs and disable the SLIRP interfaces so the shared network is the only one — mimicking a real device with one WiFi network:

```bash
# Configure the shared NIC
adb -s emulator-5554 root
adb -s emulator-5554 shell "ip link set dev eth1 up"
adb -s emulator-5554 shell "ip addr add 192.168.77.10/24 dev eth1"

adb -s emulator-5556 root
adb -s emulator-5556 shell "ip link set dev eth1 up"
adb -s emulator-5556 shell "ip addr add 192.168.77.11/24 dev eth1"

# Disable the SLIRP interfaces so NSD uses the shared NIC.
# ADB still works — it connects via the emulator console port, not the virtual network.
for serial in emulator-5554 emulator-5556; do
  adb -s $serial shell "ip link set wlan0 down"
  adb -s $serial shell "ip link set radio0 down"
done
```

Disabling the SLIRP interfaces is important: the mDNS daemon advertises the IP addresses of all active interfaces. If SLIRP is active, the resolved address will be the SLIRP IP (`10.0.2.x`), which isn't routable between emulators. With only `eth1` active, the resolved address is naturally `192.168.77.x` — no app-level workarounds needed.

## Test Results

| Test | Description | Result |
|---|---|---|
| `nsdRegistrationWorks` | Register an NSD service on a single emulator | PASS |
| `nsdDiscoveryStarts` | Start NSD discovery on a single emulator | PASS |
| `discoverServiceFromOtherEmulator` | Emulator 1 discovers Emulator 2's NSD service | PASS |
| `connectToDiscoveredService` | Discover, resolve, then make a TCP connection and exchange data | PASS |

All 4 tests pass in ~1.1 seconds. The app code uses standard `NsdManager` APIs with no test-specific workarounds.

## Approaches Compared

### QEMU Socket Multicast (recommended)

- **mDNS Discovery**: PASS
- **TCP Connection**: PASS
- **Root required**: No
- **Host setup**: None
- **App workarounds**: None — standard NSD APIs work as-is
- **How it works**: Adds a second `virtio-net-pci` NIC to each emulator connected via QEMU's `socket,mcast` backend. Disabling the SLIRP interfaces makes the shared NIC the only active network, so NSD naturally resolves to the correct address.

### TAP Bridge (`-net-tap`)

- **mDNS Discovery**: PASS
- **TCP Connection**: FAIL (timeout)
- **Root required**: Yes (TAP/bridge creation)
- **Host setup**: TAP interfaces + bridge
- **How it works**: Replaces the emulator's SLIRP backend entirely with a TAP interface on a host bridge. mDNS multicast works, but unicast TCP fails — the wifi interface (`wlan0`) doesn't properly accept static IP reconfiguration.

## Key Findings

### 1. Android NSD discovers across all interfaces

The [research](https://claude.ai/share/f8f3a55f-727c-4117-a6a0-7aa3d73f3047) predicted that NSD might only bind to interfaces recognized by `ConnectivityService`. In practice, **NSD discovers services on secondary NICs** (like `eth1` added via QEMU) even without a formal Android `Network` object. Tested on API 30.

### 2. Disabling SLIRP makes NSD resolve to the correct address

The mDNS daemon advertises addresses for all active interfaces. With both SLIRP (`wlan0`) and the shared NIC (`eth1`) active, the resolver returns the SLIRP address which isn't routable between emulators. Disabling SLIRP makes `eth1` the only active interface, so the resolved address is naturally the routable one. This avoids any app-level workarounds — the app code is identical to production.

### 3. QEMU socket multicast is undocumented but works

The Android emulator's QEMU fork supports the `socket,mcast` netdev backend despite this being completely undocumented in the Android emulator context. This is the most CI-friendly approach: no root, no host network setup, no TAP/bridge configuration.

### 4. AVD path on GitHub Actions runners

The `avdmanager` tool on GitHub Actions runners creates AVDs at `~/.config/.android/avd/` (XDG convention), not `~/.android/avd/`. Set `ANDROID_AVD_HOME` explicitly:

```yaml
env:
  ANDROID_AVD_HOME: /home/runner/.config/.android/avd
```

## Project Structure

```
app/
  src/
    main/
      java/com/example/nsdtest/
        MainActivity.kt        # NSD service registration + TCP echo server
      AndroidManifest.xml
    androidTest/
      java/com/example/nsdtest/
        NsdDiscoveryTest.kt    # 4 instrumented tests
.github/
  workflows/
    nsd-test.yml               # CI workflow with 3 jobs
```

## CI Workflow

The workflow runs 3 jobs:

1. **Baseline** — Single emulator via `reactivecircus/android-emulator-runner`, validates NSD registration and discovery work
2. **TAP Bridge** — Two emulators with `-net-tap` on a host bridge (mDNS works, TCP fails)
3. **QEMU Socket** — Two emulators with QEMU socket multicast (all 4 tests pass)
