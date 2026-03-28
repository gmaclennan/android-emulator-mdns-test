package com.example.nsdtest

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "NsdTest"
        const val SERVICE_TYPE = "_nsdtest._tcp."
        const val ECHO_PREFIX = "ECHO:"
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val serviceName = intent.getStringExtra("service_name") ?: "NsdTest-${android.os.Build.SERIAL}"
        registerNsdService(serviceName)
    }

    private fun registerNsdService(serviceName: String) {
        val statusText = findViewById<TextView>(R.id.statusText)
        val logText = findViewById<TextView>(R.id.logText)

        // Create a real listening socket — NSD requires a valid port
        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort

        // Accept TCP connections and echo back with a prefix
        thread(isDaemon = true) {
            Log.i(TAG, "Echo server listening on port $port")
            while (!serverSocket!!.isClosed) {
                try {
                    val client = serverSocket!!.accept()
                    Log.i(TAG, "Client connected from ${client.inetAddress}")
                    thread(isDaemon = true) {
                        try {
                            val reader = client.getInputStream().bufferedReader()
                            val writer = client.getOutputStream().bufferedWriter()
                            val line = reader.readLine()
                            Log.i(TAG, "Received: $line")
                            writer.write("$ECHO_PREFIX$line\n")
                            writer.flush()
                            client.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error handling client", e)
                        }
                    }
                } catch (e: Exception) {
                    if (!serverSocket!!.isClosed) {
                        Log.w(TAG, "Error accepting connection", e)
                    }
                }
            }
        }

        // Find a routable IPv4 address on the shared network interface
        // Prefer 192.168.77.x (the shared subnet), fall back to any non-loopback IPv4
        val hostAddress = findSharedAddress()
        Log.i(TAG, "Using host address: $hostAddress")

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
            if (hostAddress != null) {
                host = hostAddress
                // Also put the address in a TXT record as a fallback,
                // since setHost() may be ignored by the mDNS daemon
                setAttribute("address", hostAddress.hostAddress)
                Log.i(TAG, "Set TXT record address=${hostAddress.hostAddress}")
            }
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName} on port $port")
                runOnUiThread {
                    statusText.text = "Registered: ${info.serviceName} on port $port"
                    logText.append("Service registered: ${info.serviceName}\n")
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: errorCode=$errorCode")
                runOnUiThread {
                    statusText.text = "Registration FAILED (error $errorCode)"
                    logText.append("Registration failed: $errorCode\n")
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: errorCode=$errorCode")
            }
        }

        Log.i(TAG, "Registering service: $serviceName type=$SERVICE_TYPE port=$port")
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun findSharedAddress(): InetAddress? {
        var fallback: InetAddress? = null
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                // Prefer the shared subnet used by our CI setup
                if (addr.hostAddress?.startsWith("192.168.77.") == true) {
                    Log.i(TAG, "Found shared subnet address: ${addr.hostAddress} on ${iface.name}")
                    return addr
                }
                if (fallback == null) {
                    fallback = addr
                }
            }
        }
        Log.i(TAG, "No shared subnet address found, fallback: ${fallback?.hostAddress}")
        return fallback
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering service", e)
        }
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
    }
}
