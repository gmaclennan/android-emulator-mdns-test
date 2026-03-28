package com.example.nsdtest

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
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
