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

The QEMU instances exchange raw Ethernet frames over a host UDP multicast group, creating a shared L2 segment. The default SLIRP interface (`wlan0`) stays active for adb connectivity.

After boot, configure the second NIC inside each guest with static IPs:

```bash
adb -s emulator-5554 root
adb -s emulator-5554 shell "ip link set dev eth1 up"
adb -s emulator-5554 shell "ip addr add 192.168.77.10/24 dev eth1"

adb -s emulator-5556 root
adb -s emulator-5556 shell "ip link set dev eth1 up"
adb -s emulator-5556 shell "ip addr add 192.168.77.11/24 dev eth1"
```

## Test Results

| Test | Description | Result |
|---|---|---|
| `nsdRegistrationWorks` | Register an NSD service on a single emulator | PASS |
| `nsdDiscoveryStarts` | Start NSD discovery on a single emulator | PASS |
| `discoverServiceFromOtherEmulator` | Emulator 1 discovers Emulator 2's NSD service | PASS |
| `connectToDiscoveredService` | Discover, resolve, then make a TCP connection | PASS |

All 4 tests pass in ~1.1 seconds.

## Approaches Compared

### QEMU Socket Multicast (recommended)

- **mDNS Discovery**: PASS
- **TCP Connection**: PASS
- **Root required**: No
- **Host setup**: None
- **How it works**: Adds a second `virtio-net-pci` NIC to each emulator connected via QEMU's `socket,mcast` backend. Both NICs share a virtual L2 segment.

### TAP Bridge (`-net-tap`)

- **mDNS Discovery**: PASS
- **TCP Connection**: FAIL (timeout)
- **Root required**: Yes (TAP/bridge creation)
- **Host setup**: TAP interfaces + bridge + dnsmasq
- **How it works**: Replaces the emulator's SLIRP backend entirely with a TAP interface on a host bridge. mDNS multicast works, but unicast TCP fails — likely because Android's wifi stack conflicts with the static IP assignment on `wlan0`.

## Key Findings

### 1. Android NSD discovers across all interfaces

The research predicted that NSD might only bind to interfaces recognized by `ConnectivityService`. In practice, **NSD discovers services on secondary NICs** (like `eth1` added via QEMU) even without a formal Android `Network` object. Tested on API 30.

### 2. `NsdServiceInfo.setHost()` is ignored on registration

The mDNS daemon always advertises the primary interface's address, regardless of what you pass to `setHost()`. On QEMU socket, this means the resolved address is the SLIRP IP (`10.0.2.16`), which isn't routable between emulators.

**Workaround**: Store the routable IP in an NSD TXT record attribute:

```kotlin
// Registration (on the advertising device)
val serviceInfo = NsdServiceInfo().apply {
    serviceName = "MyService"
    serviceType = "_myservice._tcp."
    port = serverSocket.localPort
    setAttribute("address", "192.168.77.11")  // routable address
}

// Resolution (on the discovering device)
val txtAddress = resolvedInfo.attributes["address"]?.let { String(it) }
val host = if (txtAddress != null) {
    InetAddress.getByName(txtAddress)
} else {
    resolvedInfo.host
}
```

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
3. **QEMU Socket** — Two emulators with QEMU socket multicast (everything works)
