# Cross-Emulator NSD Testing with React Native: Architecture Plan

## Overview

This document describes an architecture for testing NSD (Network Service Discovery) advertising and discovery between two Android emulators in GitHub CI, using **react-native-harness** and **react-native-zeroconf**. The app code uses standard APIs with no test-specific workarounds.

## The Core Challenge

`react-native-harness` runs tests on **one device at a time** per runner instance. For cross-emulator NSD testing, we need:

- Emulator 2 advertising a service
- Emulator 1 discovering and connecting to it
- Both running simultaneously with coordinated timing

## Solution: Harness + App Launch

Run `react-native-harness` on **one** emulator (the discoverer) and simply launch the RN app on the other emulator via `adb shell am start` (the advertiser).

This avoids a key limitation: two simultaneous harness instances would conflict on the WebSocket bridge port (`3001` by default) on the host. Since `react-native-harness` communicates with the device over a WebSocket bridge (using `adb reverse` for port forwarding), running two instances requires different ports and configs — unnecessary complexity.

The simpler approach is also more realistic: one device advertises a service (just by running the app), the other runs test assertions against the discovery flow.

```
┌─────────────────────────────────────────────────────────────────┐
│  GitHub Actions Runner                                          │
│                                                                 │
│  ┌──────────────────────┐     ┌──────────────────────┐         │
│  │ Harness (Jest)       │     │ adb shell am start   │         │
│  │ discover.test.ts     │     │ (launches RN app)    │         │
│  │ --harnessRunner emu1 │     │                      │         │
│  └──────────┬───────────┘     └──────────┬───────────┘         │
│             │ WebSocket + adb reverse     │ Just running        │
│  ┌──────────▼───────────┐     ┌──────────▼───────────┐         │
│  │ Emulator 1 (avd1)    │     │ Emulator 2 (avd2)    │         │
│  │ emulator-5554        │     │ emulator-5556        │         │
│  │                      │     │                      │         │
│  │ eth1: 192.168.77.10  │◄───►│ eth1: 192.168.77.11  │         │
│  │                      │ QEMU│                      │         │
│  │ Discovers service    │mcast│ Advertises service   │         │
│  │ Resolves host:port   │     │ Runs TCP echo server │         │
│  │ TCP connects + data  │     │ (via react-native-   │         │
│  │                      │     │  zeroconf + tcp-sock) │         │
│  └──────────────────────┘     └──────────────────────┘         │
│                                                                 │
│  QEMU Socket Multicast (230.0.0.1:1234) — shared L2 segment    │
└─────────────────────────────────────────────────────────────────┘
```

### Why not two harness instances?

`react-native-harness` uses a WebSocket bridge (default port 3001) and `adb reverse` to communicate between the host and device. Running two harness instances simultaneously would require:

- Different `webSocketPort` values per instance
- Separate config files (the port is a global config, not per-runner)
- Coordination to avoid Metro port conflicts

This adds complexity for no real benefit. The advertise side doesn't need test assertions — it just needs to run the app. Our prototype proved this works with a simple `adb shell am start`.

### How `adb reverse` works with QEMU socket multicast

`adb reverse tcp:8081 tcp:8081` creates a tunnel from the device to the host through the ADB channel. This operates via the emulator console port (host-local TCP), **not** through the emulator's virtual network. Disabling SLIRP (`wlan0`/`radio0`) does not affect:

- `adb` commands (including `adb shell`, `adb install`)
- `adb reverse` port forwarding (used by Metro and harness bridge)
- `adb forward` port forwarding

This means `react-native-harness` works normally on emu1 even though SLIRP is disabled — Metro bundling, WebSocket bridge, and all test communication flow through ADB.

## Project Structure

```
src/
  main.tsx                        # App entry point
  App.tsx                         # App component — publishes NSD + runs echo server
tests/
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
  ],
  bridgeTimeout: 120_000,
  forwardClientLogs: true,
};
```

### `jest.harness.config.mjs`

```js
export default {
  preset: 'react-native-harness/jest-preset',
  testTimeout: 180_000,
};
```

## App Code (runs on both emulators)

The same RN app is installed on both emulators. On emu2, it's launched via `adb shell am start` and simply runs — publishing an NSD service and accepting TCP connections. On emu1, the harness injects tests that use `react-native-zeroconf` to discover and connect.

### `src/App.tsx`

```tsx
import React, { useEffect } from 'react';
import { View, Text } from 'react-native';
import Zeroconf from 'react-native-zeroconf';
import TcpSocket from 'react-native-tcp-socket';

export default function App() {
  useEffect(() => {
    // Start TCP echo server
    const server = TcpSocket.createServer((socket) => {
      socket.on('data', (data) => {
        socket.write('ECHO:' + data.toString());
      });
    }).listen({ port: 0, host: '0.0.0.0' });

    server.on('listening', () => {
      const port = server.address()?.port;
      if (!port) return;

      // Publish NSD service on the actual listening port
      const zeroconf = new Zeroconf();
      zeroconf.publishService('nsdtest', 'tcp', 'local.', 'TestService', port, {
        version: '1',
      });
    });

    return () => { server.close(); };
  }, []);

  return (
    <View><Text>NSD Test — Advertising Service</Text></View>
  );
}
```

## Test File

### `tests/discover.test.ts` — runs on emu1 via harness

```ts
import { describe, it, expect, harness } from 'react-native-harness';
import Zeroconf from 'react-native-zeroconf';

describe('NSD Cross-Emulator Discovery', () => {
  it('discovers a service advertised by another emulator', async () => {
    const zeroconf = new Zeroconf();

    const service = await new Promise<any>((resolve, reject) => {
      const timeout = setTimeout(() => {
        zeroconf.stop();
        reject(new Error('Discovery timed out after 120s'));
      }, 120_000);

      zeroconf.on('resolved', (svc) => {
        if (svc.name.includes('TestService')) {
          clearTimeout(timeout);
          zeroconf.stop();
          resolve(svc);
        }
      });
      zeroconf.on('error', (err) => {
        clearTimeout(timeout);
        reject(err);
      });
      zeroconf.scan('nsdtest', 'tcp', 'local.');
    });

    expect(service).toBeDefined();
    expect(service.name).toContain('TestService');
    expect(service.port).toBeGreaterThan(0);
    expect(service.addresses.length).toBeGreaterThan(0);

    // Verify the resolved address is on the shared subnet
    const sharedAddr = service.addresses.find(
      (a: string) => a.startsWith('192.168.77.')
    );
    expect(sharedAddr).toBeDefined();

    zeroconf.removeDeviceListeners();
  }, 180_000);

  it('makes a TCP connection to the discovered service', async () => {
    const zeroconf = new Zeroconf();
    const TcpSocket = require('react-native-tcp-socket');

    // Discover
    const service = await new Promise<any>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout')), 120_000);
      zeroconf.on('resolved', (svc) => {
        if (svc.name.includes('TestService')) {
          clearTimeout(timeout);
          zeroconf.stop();
          resolve(svc);
        }
      });
      zeroconf.scan('nsdtest', 'tcp', 'local.');
    });

    // Connect and exchange data
    const sharedAddr = service.addresses.find(
      (a: string) => a.startsWith('192.168.77.')
    );

    const response = await new Promise<string>((resolve, reject) => {
      const client = TcpSocket.createConnection(
        { host: sharedAddr, port: service.port },
        () => { client.write('hello-from-emu1'); }
      );
      client.on('data', (data: Buffer) => {
        resolve(data.toString());
        client.destroy();
      });
      client.on('error', reject);
    });

    expect(response).toBe('ECHO:hello-from-emu1');

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

      - name: Build Android app
        run: |
          cd android && ./gradlew assembleDebug

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

      - name: Install app on emu2 and launch (advertiser)
        run: |
          adb -s emulator-5556 install app/build/outputs/apk/debug/app-debug.apk
          adb -s emulator-5556 shell am start -n com.nsdtest/.MainActivity
          sleep 10

          # Verify the service is registered
          adb -s emulator-5556 logcat -d -s NsdTest:* | tail -10

      - name: Run discovery tests on emu1 via harness
        run: |
          npx react-native-harness --harnessRunner emu1 tests/discover.test.ts
```

## Coordination Strategy

No explicit coordination mechanism is needed:

1. The **app on emu2** is launched via `adb shell am start` and registers its NSD service immediately (<1s)
2. The **harness on emu1** starts after a 10-second buffer and runs the discover test with a 120s timeout
3. Discovery happens in <2s once both are running (proven in our prototype)

```
Timeline:
  t=0s    CI installs app on emu2, launches it
  t=1s    Emu2 publishes NSD service + starts TCP echo server
  t=10s   CI starts harness on emu1 (discover.test.ts)
  t=15s   Harness boots, test begins scanning
  t=16s   Emu1 discovers Emu2's service
  t=16s   Emu1 resolves → 192.168.77.11:PORT
  t=16s   Emu1 connects via TCP, exchanges data — PASS
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

### Does `adb reverse` still work?

Yes. `adb reverse` operates through the ADB channel (emulator console port), not through the virtual network. Disabling SLIRP does not affect:

- `adb` commands (`adb shell`, `adb install`, `adb logcat`)
- `adb reverse` port forwarding (used by Metro bundler and react-native-harness bridge)
- `adb forward` port forwarding

The react-native-harness WebSocket bridge and Metro bundler connection both flow through `adb reverse`, which is unaffected by the virtual network configuration.

## Key Technical Findings

These findings come from the [prototype testing](https://github.com/gmaclennan/android-emulator-mdns-test/pull/1):

1. **`NsdServiceInfo.setHost()` is ignored on registration** (API 30). In `MDnsSdListener.cpp`, the `host` parameter is hardcoded to `NULL` when calling `DNSServiceRegister()`. Fixed in API 34 with `setHostAddresses()`.

2. **mDNSResponder advertises on all active interfaces** that are UP and not point-to-point. It enumerates interfaces via Netlink and operates at the Linux socket level, independent of Android's `ConnectivityService`.

3. **Android NSD discovers across all interfaces**, including secondary NICs not managed by `ConnectivityService`. The mDNSResponder daemon doesn't require a formal Android `Network` object.

4. **QEMU socket multicast is undocumented but works** with the Android emulator's QEMU fork. The `socket,mcast` netdev backend is the most CI-friendly approach — no root, no host network setup.

5. **AVD path on GitHub Actions**: `avdmanager` creates AVDs at `~/.config/.android/avd/` (XDG convention). Set `ANDROID_AVD_HOME` explicitly.

6. **ADB is unaffected by virtual network changes**. ADB connects through the emulator console port (host-local TCP), not through the emulator's virtual network.

## Design Decisions

1. **One harness instance, not two**: Running two harness instances would cause WebSocket bridge port conflicts. Instead, emu2 just runs the app normally via `adb shell am start`.

2. **No explicit coordination**: The mDNS discovery timeout (120s) handles timing naturally. The app on emu2 publishes instantly; the test on emu1 scans with a generous timeout.

3. **SLIRP disabled at CI level, not in app code**: The emulator environment setup is purely infrastructure. The app and test code use standard `react-native-zeroconf` APIs.

4. **Same APK on both emulators**: The app advertises an NSD service and runs a TCP echo server. On emu1, the harness injects tests that discover and connect. On emu2, the app just runs.
