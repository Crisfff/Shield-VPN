package com.shield.vpn

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

class WireGuardManager(private val context: Context) {

    companion object {
        private var backend: GoBackend? = null
        private var tunnel: Tunnel? = null

        private fun getBackend(context: Context): GoBackend {
            if (backend == null) {
                backend = GoBackend(context.applicationContext)
            }
            return backend!!
        }

        private fun getTunnel(): Tunnel {
            if (tunnel == null) {
                tunnel = object : Tunnel {
                    override fun getName(): String = "ShieldTunnel"

                    override fun onStateChange(newState: Tunnel.State) {
                        Log.d("WG", "Estado del túnel: $newState")
                    }
                }
            }
            return tunnel!!
        }

        fun connectStatic(context: Context, configText: String) {
            try {
                val backend = getBackend(context)
                val tunnel = getTunnel()
                val config = Config.parse(configText.byteInputStream())
                backend.setState(tunnel, Tunnel.State.UP, config)
                Log.d("WG", "Conectado correctamente desde static")
            } catch (e: Exception) {
                Log.e("WG_ERROR", "Error conectando desde static", e)
                throw e
            }
        }

        fun disconnectStatic(context: Context) {
            try {
                val backend = getBackend(context)
                val tunnel = getTunnel()
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                Log.d("WG", "Desconectado correctamente desde static")
            } catch (e: Exception) {
                Log.e("WG_ERROR", "Error desconectando desde static", e)
                throw e
            }
        }
    }

    fun connect(configText: String) {
        connectStatic(context, configText)
    }

    fun disconnect() {
        disconnectStatic(context)
    }
}
