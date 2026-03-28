# Cross-Emulator NSD Testing with React Native: Architecture Plan

## Overview

This document describes an architecture for testing NSD (Network Service Discovery) advertising and discovery between two Android emulators in GitHub CI, using **react-native-harness** and **react-native-zeroconf**. The app code uses standard APIs with no test-specific workarounds.

## The Core Challenge

`react-native-harness` runs tests on **one device at a time** per runner instance. For cross-emulator NSD testing, we need:

- Emulator 2 advertising a service
- Emulator 1 discovering and connecting to it
- Both running simultaneously with coordinated timing
- Combined test results at the end

## Solution: Parallel Harness Runners

Define two runners in the harness config, each targeting a different emulator. Run them in parallel from the CI workflow, with each executing a different test file.

```
┌─────────────────────────────────────────────────────────────────┐
│  GitHub Actions Runner                                          │
│                                                                 │
│  ┌──────────────────────┐     ┌──────────────────────┐         │
│  │ Harness (Jest) #1    │     │ Harness (Jest) #2    │         │
│  │ discover.test.ts     │     │ advertise.test.ts    │         │
│  │ --harnessRunner emu1 │     │ --harnessRunner emu2 │         │
│  └──────────┬───────────┘     └──────────┬───────────┘         │
│             │ WebSocket                   │ WebSocket            │
│  ┌──────────▼───────────┐     ┌──────────▼───────────┐         │
│  │ Emulator 1 (avd1)    │     │ Emulator 2 (avd2)    │         │
│  │ emulator-5554        │     │ emulator-5556        │         │
│  │                      │     │                      │         │
│  │ eth1: 192.168.77.10  │◄───►│ eth1: 192.168.77.11  │         │
│  │                      │ QEMU│                      │         │
│  │ Discovers service    │mcast│ Advertises service   │         │
│  │ Resolves host:port   │     │ Runs TCP echo server │         │
│  │ TCP connects + data  │     │                      │         │
│  └──────────────────────┘     └──────────────────────┘         │
│                                                                 │
│  QEMU Socket Multicast (230.0.0.1:1234) — shared L2 segment    │
└─────────────────────────────────────────────────────────────────┘
```

## Project Structure

```
src/
  main.tsx                        # App entry point (minimal, needed by harness)
tests/
  advertise.test.ts               # Runs on emu2: publish service, keep alive
  discover.test.ts                # Runs on emu1: discover, resolve, TCP connect
rn-harness.config.mjs
jest.harness.config.mjs
android/                          # React Native android project
package.json
.github/
  workflows/
    nsd-test.yml
```

## Configuration

### `rn-harness.config.mjs`

```js
import { androidPlatform, androidEmulator } from '@react-native-harness/platform-android';

export default {
  entryPoint: './src/main.tsx',
  appRegistryComponentName: 'NsdTestApp',
  runners: [
    androidPlatform({
      name: 'emu1',
      device: androidEmulator('avd1'),
      bundleId: 'com.nsdtest',
    }),
    androidPlatform({
      name: 'emu2',
      device: androidEmulator('avd2'),
      bundleId: 'com.nsdtest',
    }),
  ],
  bridgeTimeout: 120_000,         // generous timeout for CI
  forwardClientLogs: true,        // see device logs in CI output
};
```

### `jest.harness.config.mjs`

```js
export default {
  preset: 'react-native-harness/jest-preset',
  testTimeout: 180_000,           // 3min per test (discovery has 120s timeout)
};
```

## Test Files

### `tests/advertise.test.ts` — runs on emu2

```ts
import { describe, it, expect } from 'react-native-harness';
import Zeroconf from 'react-native-zeroconf';

describe('NSD Advertise', () => {
  it('registers a discoverable service and keeps it alive', async () => {
    const zeroconf = new Zeroconf();

    const published = new Promise<void>((resolve, reject) => {
      zeroconf.on('published', () => resolve());
      zeroconf.on('error', (err) => reject(err));
    });

    // Publish on port 12345 — for TCP tests, you'd also start
    // a server here using react-native-tcp-socket
    zeroconf.publishService('nsdtest', 'tcp', 'local.', 'TestService-Emu2', 12345, {
      version: '1',
    });

    await published;

    // Keep the service alive long enough for the discover test to find it.
    // The discover test has a 120s timeout, so 90s of alive time is sufficient
    // since both tests start within seconds of each other.
    await new Promise((resolve) => setTimeout(resolve, 90_000));

    zeroconf.unpublishService('TestService-Emu2');
    zeroconf.removeDeviceListeners();
  }, 120_000);
});
```

### `tests/discover.test.ts` — runs on emu1

```ts
import { describe, it, expect, harness } from 'react-native-harness';
import Zeroconf from 'react-native-zeroconf';

describe('NSD Discover', () => {
  it('discovers a service from another emulator', async () => {
    const zeroconf = new Zeroconf();

    const resolved = await harness.waitFor(() => {
      return new Promise<any>((resolve, reject) => {
        zeroconf.on('resolved', (service) => {
          if (service.name.includes('Emu2')) {
            resolve(service);
          }
        });
        zeroconf.on('error', reject);
        zeroconf.scan('nsdtest', 'tcp', 'local.');
      });
    }, { timeout: 120_000, interval: 5_000 });

    expect(resolved).toBeDefined();
    expect(resolved.name).toContain('Emu2');
    expect(resolved.port).toBe(12345);
    expect(resolved.addresses.length).toBeGreaterThan(0);
    // The resolved address should be on the shared 192.168.77.x subnet
    expect(
      resolved.addresses.some((a: string) => a.startsWith('192.168.77.'))
    ).toBe(true);

    zeroconf.stop();
    zeroconf.removeDeviceListeners();
  }, 180_000);
});
```

## CI Workflow

### `.github/workflows/nsd-test.yml`

```yaml
name: RN NSD Discovery Test
on:
  push:
    branches: [main]
  pull_request:

jobs:
  cross-emulator-nsd:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    env:
      ANDROID_AVD_HOME: /home/runner/.config/.android/avd

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: actions/setup-node@v4
        with: { node-version: 18 }
      - uses: android-actions/setup-android@v3

      - run: echo "$ANDROID_HOME/emulator" >> $GITHUB_PATH

      - name: Install dependencies
        run: npm install

      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - name: Install system image & create AVDs
        run: |
          sdkmanager --install "system-images;android-30;default;x86_64"
          echo "no" | avdmanager create avd -n avd1 \
            -k "system-images;android-30;default;x86_64" --force
          echo "no" | avdmanager create avd -n avd2 \
            -k "system-images;android-30;default;x86_64" --force

      - name: Start emulators with QEMU socket multicast
        run: |
          emulator @avd1 -no-window -no-audio -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot \
            -qemu -device virtio-net-pci,netdev=lan,mac=52:54:00:12:34:56 \
            -netdev socket,id=lan,mcast=230.0.0.1:1234 &

          sleep 5

          emulator @avd2 -no-window -no-audio -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot \
            -qemu -device virtio-net-pci,netdev=lan,mac=52:54:00:12:34:57 \
            -netdev socket,id=lan,mcast=230.0.0.1:1234 &

      - name: Wait for boot & configure networking
        run: |
          for serial in emulator-5554 emulator-5556; do
            timeout 300 adb -s $serial wait-for-device
            while [ "$(adb -s $serial shell getprop sys.boot_completed \
              2>/dev/null | tr -d '\r')" != "1" ]; do sleep 5; done
          done

          adb -s emulator-5554 root; adb -s emulator-5556 root; sleep 2

          # Configure shared NIC
          adb -s emulator-5554 shell "ip link set eth1 up"
          adb -s emulator-5554 shell "ip addr add 192.168.77.10/24 dev eth1"
          adb -s emulator-5556 shell "ip link set eth1 up"
          adb -s emulator-5556 shell "ip addr add 192.168.77.11/24 dev eth1"

          # Disable SLIRP so NSD resolves to the shared NIC address
          for serial in emulator-5554 emulator-5556; do
            adb -s $serial shell "ip link set wlan0 down"
            adb -s $serial shell "ip link set radio0 down"
          done
          sleep 5

      - name: Run NSD tests in parallel
        run: |
          # Run advertise test on emu2 (background)
          npx react-native-harness --harnessRunner emu2 \
            --json --outputFile /tmp/emu2-results.json \
            tests/advertise.test.ts &
          EMU2_PID=$!

          # Give emu2 a moment to start publishing
          sleep 10

          # Run discover test on emu1 (foreground)
          npx react-native-harness --harnessRunner emu1 \
            --json --outputFile /tmp/emu1-results.json \
            tests/discover.test.ts
          EMU1_EXIT=$?

          # Wait for emu2's advertise test to finish
          wait $EMU2_PID
          EMU2_EXIT=$?

          # Report results
          echo "=== Emu1 (discover) exit: $EMU1_EXIT ==="
          cat /tmp/emu1-results.json 2>/dev/null \
            | jq '.testResults[].testResults[] | {name: .title, status: .status}' \
            || true
          echo "=== Emu2 (advertise) exit: $EMU2_EXIT ==="
          cat /tmp/emu2-results.json 2>/dev/null \
            | jq '.testResults[].testResults[] | {name: .title, status: .status}' \
            || true

          # Fail if either test failed
          [ $EMU1_EXIT -eq 0 ] && [ $EMU2_EXIT -eq 0 ]
```

## Coordination Strategy

No explicit coordination mechanism is needed because the timing works naturally:

1. Both harness processes start within seconds
2. The **advertise test** registers a service immediately (<1s) and then sleeps for 90s
3. The **discover test** scans with a 120s timeout — plenty of time to find the service
4. Discovery happens in <2s once both are running (proven in our prototype)

The 10-second `sleep` between starting emu2 and emu1 provides a buffer, but even without it, the 120s discovery timeout handles any startup variance.

```
Timeline:
  t=0s    CI starts emu2 harness (advertise.test.ts)
  t=1s    Emu2 publishes NSD service
  t=10s   CI starts emu1 harness (discover.test.ts)
  t=11s   Emu1 starts scanning, discovers Emu2's service
  t=12s   Emu1 resolves service, gets 192.168.77.11:12345
  t=12s   Emu1 connects via TCP, exchanges data — PASS
  t=90s   Emu2's keep-alive timer expires, unpublishes — PASS
```

## Extending with TCP Connection Testing

To test TCP connections after discovery (the full NSD workflow), add `react-native-tcp-socket`:

### In `tests/advertise.test.ts`

```ts
import TcpSocket from 'react-native-tcp-socket';

// Start echo server before publishing
const server = TcpSocket.createServer((socket) => {
  socket.on('data', (data) => {
    socket.write('ECHO:' + data.toString());
  });
}).listen({ port: 12345, host: '0.0.0.0' });

// Then publish the NSD service as before
zeroconf.publishService('nsdtest', 'tcp', 'local.', 'TestService-Emu2', 12345);
```

### In `tests/discover.test.ts`

```ts
import TcpSocket from 'react-native-tcp-socket';

// After discovery and resolution...
const response = await new Promise<string>((resolve, reject) => {
  const client = TcpSocket.createConnection(
    { host: resolved.addresses[0], port: resolved.port },
    () => { client.write('hello-from-emu1'); }
  );
  client.on('data', (data) => {
    resolve(data.toString());
    client.destroy();
  });
  client.on('error', reject);
});

expect(response).toBe('ECHO:hello-from-emu1');
```

## Emulator Networking Setup (from prototype findings)

The networking uses **QEMU socket multicast**, which creates a shared L2 segment between emulators without root or host network configuration:

| Step | What | Why |
|------|------|-----|
| Add `-qemu -device virtio-net-pci -netdev socket,mcast=...` | Adds a second NIC (`eth1`) to each emulator | Creates a shared network for mDNS multicast |
| Assign static IPs to `eth1` | `192.168.77.10` and `192.168.77.11` | Routable addresses on the shared segment |
| Disable `wlan0` and `radio0` | `ip link set wlan0 down` | Forces mDNS to advertise on `eth1` only |

### Why disable SLIRP?

The mDNS daemon (mDNSResponder on API 30) advertises on **all active interfaces**. If SLIRP (`wlan0`) is active alongside `eth1`, the resolver returns the SLIRP IP (`10.0.2.x`), which is identical on both emulators and not routable between them. Disabling SLIRP makes `eth1` the only active interface, so the resolved address is naturally `192.168.77.x`.

This is purely infrastructure setup — the app code uses standard `react-native-zeroconf` APIs with no workarounds.

### Why not TAP bridge?

We tested both approaches:

| | QEMU Socket Multicast | TAP Bridge (`-net-tap`) |
|---|---|---|
| mDNS Discovery | PASS | PASS |
| TCP Connection | PASS | FAIL (timeout) |
| Root required | No | Yes |
| Host setup | None | TAP + bridge |
| Recommended | **Yes** | No |

The TAP bridge approach works for mDNS discovery but fails for TCP — the wifi driver (`wlan0`) doesn't properly accept static IP reconfiguration.

## Key Technical Findings

These findings come from the [prototype testing](https://github.com/gmaclennan/android-emulator-mdns-test/pull/1):

1. **`NsdServiceInfo.setHost()` is ignored on registration** (API 30). The `host` field in `MDnsSdListener.cpp` is hardcoded to `NULL`. Fixed in API 34 with `setHostAddresses()`.

2. **Android NSD discovers across all interfaces**, including secondary NICs not managed by `ConnectivityService`. The mDNSResponder daemon operates at the Linux socket layer, not through Android's network abstraction.

3. **QEMU socket multicast is undocumented but works** with the Android emulator's QEMU fork. The `socket,mcast` netdev backend is the most CI-friendly approach.

4. **AVD path on GitHub Actions**: `avdmanager` creates AVDs at `~/.config/.android/avd/` (XDG convention). Set `ANDROID_AVD_HOME` explicitly.

5. **ADB is unaffected by virtual network changes**. ADB connects through the emulator console port (host-local TCP), not through the emulator's virtual network. Disabling SLIRP or adding NICs doesn't break adb.

## Design Decisions

1. **Separate test files, not separate test cases**: Each emulator runs a different `.test.ts` file. This is the cleanest way to partition work across devices with `react-native-harness`.

2. **No explicit coordination server**: The mDNS discovery timeout handles timing naturally. Adding a coordination mechanism (HTTP server, file polling) adds complexity without benefit.

3. **`--json --outputFile` for result aggregation**: Jest's JSON output lets the CI combine results from both runners and fail if either fails.

4. **SLIRP disabled at CI level, not in app code**: The emulator environment setup is purely infrastructure. The app and test code use standard `react-native-zeroconf` APIs with no test-specific workarounds.

5. **The advertise test is also a test**: It asserts that publishing succeeds. If NSD registration fails, this test fails too, giving clear diagnostics.
