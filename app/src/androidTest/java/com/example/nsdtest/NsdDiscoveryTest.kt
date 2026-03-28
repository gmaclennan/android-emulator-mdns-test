package com.example.nsdtest

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NsdDiscoveryTest {

    companion object {
        const val TAG = "NsdDiscoveryTest"
        const val SERVICE_TYPE = "_nsdtest._tcp."
        const val DISCOVERY_TIMEOUT_SECONDS = 120L
    }

    /**
     * This test registers its own NSD service AND discovers services.
     * When run on emulator 1 while emulator 2 has the app running (which also registers a service),
     * this test should discover emulator 2's service.
     *
     * The test also registers a service so that emulator 2's app (if it were also discovering)
     * could find emulator 1.
     */
    @Test
    fun discoverServiceFromOtherEmulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        // Register our own service
        val serverSocket = ServerSocket(0)
        val ownServiceName = "NsdTest-Tester"
        val ownServiceInfo = NsdServiceInfo().apply {
            serviceName = ownServiceName
            serviceType = SERVICE_TYPE
            port = serverSocket.localPort
        }

        val registrationLatch = CountDownLatch(1)
        var registeredName: String? = null

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Log.i(TAG, "Own service registered: ${info.serviceName}")
                registrationLatch.countDown()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Own service registration failed: $errorCode")
                registrationLatch.countDown()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(ownServiceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        assertTrue(
            "Own service should register within 30s",
            registrationLatch.await(30, TimeUnit.SECONDS)
        )
        Log.i(TAG, "Own service registered as: $registeredName")

        // Discover services
        val discoveredServices = ConcurrentLinkedQueue<NsdServiceInfo>()
        val discoveryLatch = CountDownLatch(1)
        var discoveryStarted = false

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started for: $serviceType")
                discoveryStarted = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                // Filter out our own service
                if (serviceInfo.serviceName != registeredName) {
                    Log.i(TAG, "Found OTHER service: ${serviceInfo.serviceName}")
                    discoveredServices.add(serviceInfo)
                    discoveryLatch.countDown()
                } else {
                    Log.i(TAG, "Ignoring own service: ${serviceInfo.serviceName}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped for: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                fail("Discovery failed to start with error code $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        Log.i(TAG, "Starting discovery for $SERVICE_TYPE")
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        try {
            val found = discoveryLatch.await(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            Log.i(TAG, "Discovery result: found=$found, services=${discoveredServices.map { it.serviceName }}")

            assertTrue(
                "Should discover at least one NSD service from another emulator within ${DISCOVERY_TIMEOUT_SECONDS}s. " +
                    "Discovered: ${discoveredServices.map { it.serviceName }}",
                found && discoveredServices.isNotEmpty()
            )
        } finally {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) { Log.w(TAG, "stopDiscovery error", e) }
            try { nsdManager.unregisterService(registrationListener) } catch (e: Exception) { Log.w(TAG, "unregister error", e) }
            try { serverSocket.close() } catch (e: Exception) { Log.w(TAG, "close socket error", e) }
        }
    }

    /**
     * Simpler test: just verify NSD discovery can start successfully.
     * This helps diagnose if the NSD stack itself is functional, independent of cross-emulator networking.
     */
    @Test
    fun nsdDiscoveryStarts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val startedLatch = CountDownLatch(1)
        var startError: Int? = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started OK for: $serviceType")
                startedLatch.countDown()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found during start test: ${serviceInfo.serviceName}")
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                startError = errorCode
                startedLatch.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        assertTrue("Discovery should start within 10s", startedLatch.await(10, TimeUnit.SECONDS))

        try { nsdManager.stopServiceDiscovery(listener) } catch (e: Exception) { /* ignore */ }

        assertTrue("Discovery should start without errors, got error: $startError", startError == null)
    }

    /**
     * Verify that registering a service works on this emulator.
     */
    @Test
    fun nsdRegistrationWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serverSocket = ServerSocket(0)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "NsdTest-RegTest"
            serviceType = SERVICE_TYPE
            port = serverSocket.localPort
        }

        val latch = CountDownLatch(1)
        var registered = false
        var errorCode: Int? = null

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registration test: registered ${info.serviceName}")
                registered = true
                latch.countDown()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Registration test failed: $code")
                errorCode = code
                latch.countDown()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        assertTrue("Registration should complete within 15s", latch.await(15, TimeUnit.SECONDS))

        try { nsdManager.unregisterService(listener) } catch (e: Exception) { /* ignore */ }
        try { serverSocket.close() } catch (e: Exception) { /* ignore */ }

        assertTrue("Service should register successfully, got error: $errorCode", registered)
    }

    /**
     * Discover a service from the other emulator, resolve it to get host/port,
     * then make a TCP connection and verify data exchange.
     */
    @Test
    fun connectToDiscoveredService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        // Step 1: Discover the service from the other emulator
        val discoveredService = ConcurrentLinkedQueue<NsdServiceInfo>()
        val discoveryLatch = CountDownLatch(1)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "[TCP] Discovery started")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "[TCP] Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.contains("Emulator2") ||
                    (!serviceInfo.serviceName.contains("Tester") && !serviceInfo.serviceName.contains("RegTest"))) {
                    discoveredService.add(serviceInfo)
                    discoveryLatch.countDown()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                fail("[TCP] Discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        assertTrue(
            "[TCP] Should discover service within ${DISCOVERY_TIMEOUT_SECONDS}s",
            discoveryLatch.await(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )

        val foundService = discoveredService.poll()
        assertNotNull("[TCP] Discovered service should not be null", foundService)
        Log.i(TAG, "[TCP] Found service: ${foundService!!.serviceName}, resolving...")

        // Step 2: Resolve the service to get host and port
        val resolveLatch = CountDownLatch(1)
        var resolvedInfo: NsdServiceInfo? = null
        var resolveError: Int? = null

        nsdManager.resolveService(foundService, object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "[TCP] Resolved: host=${serviceInfo.host}, port=${serviceInfo.port}")
                resolvedInfo = serviceInfo
                resolveLatch.countDown()
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[TCP] Resolve failed: $errorCode")
                resolveError = errorCode
                resolveLatch.countDown()
            }
        })

        assertTrue(
            "[TCP] Service should resolve within 30s",
            resolveLatch.await(30, TimeUnit.SECONDS)
        )
        assertNotNull("[TCP] Resolve should succeed, got error: $resolveError", resolvedInfo)

        val resolvedHost = resolvedInfo!!.host
        val port = resolvedInfo!!.port

        // The mDNS daemon may return an unusable address (IPv6 link-local or SLIRP IP).
        // Check the TXT record for an explicit routable address.
        val txtAddress = resolvedInfo!!.attributes["address"]?.let { String(it) }
        val host = if (txtAddress != null) {
            Log.i(TAG, "[TCP] Using TXT record address: $txtAddress (resolved was: $resolvedHost)")
            java.net.InetAddress.getByName(txtAddress)
        } else {
            Log.i(TAG, "[TCP] No TXT address, using resolved: $resolvedHost")
            resolvedHost
        }
        Log.i(TAG, "[TCP] Connecting to $host:$port")

        // Step 3: Make TCP connection and exchange data
        try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 10_000)
            Log.i(TAG, "[TCP] Connected to $host:$port")

            val writer = socket.getOutputStream().bufferedWriter()
            val reader = socket.getInputStream().bufferedReader()

            val testMessage = "hello-from-emulator1"
            writer.write("$testMessage\n")
            writer.flush()
            Log.i(TAG, "[TCP] Sent: $testMessage")

            val response = reader.readLine()
            Log.i(TAG, "[TCP] Received: $response")

            socket.close()

            assertNotNull("[TCP] Should receive a response", response)
            assertEquals(
                "[TCP] Response should echo back with prefix",
                "ECHO:$testMessage",
                response
            )
            Log.i(TAG, "[TCP] TCP connection test PASSED")
        } catch (e: Exception) {
            Log.e(TAG, "[TCP] Connection failed", e)
            fail("[TCP] Failed to connect to $host:$port - ${e.message}")
        } finally {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) { /* ignore */ }
        }
    }
}
