package com.geniex.demo.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.geniex.demo.databinding.ActivityServerBinding
import com.geniex.demo.model.AppPreferences
import java.security.SecureRandom

class ServerActivity : FragmentActivity() {
    private lateinit var binding: ActivityServerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnServerBack.setOnClickListener { finish() }
        binding.etServerPort.setText(AppPreferences.getServerPort(this).toString())
        binding.switchLan.isChecked = AppPreferences.isLanEnabled(this)
        binding.etApiKey.setText(AppPreferences.getApiKey(this))
        binding.btnGenerateApiKey.setOnClickListener {
            binding.etApiKey.setText(generateKey())
        }
        binding.btnServerToggle.setOnClickListener {
            if (LocalApiServer.isRunning()) stopServer() else startServer()
        }
        binding.btnCopyWebUrl.setOnClickListener { copy("Web chat URL", LocalApiServer.webUrl()) }
        binding.btnOpenWebUi.setOnClickListener { openWebUi() }
        binding.btnCopyApiUrl.setOnClickListener { copy("API URL", LocalApiServer.apiUrl()) }
        binding.btnCopyApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString().orEmpty()
            if (key.isBlank()) Toast.makeText(this, "No API key is configured.", Toast.LENGTH_SHORT).show()
            else copy("API key", key)
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun startServer() {
        val port = binding.etServerPort.text?.toString()?.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            binding.etServerPort.error = "Use a port from 1024 to 65535"
            return
        }
        val lan = binding.switchLan.isChecked
        var key = binding.etApiKey.text?.toString()?.trim().orEmpty()
        if (lan && key.isBlank()) {
            key = generateKey()
            binding.etApiKey.setText(key)
            Toast.makeText(this, "LAN mode requires authentication; an API key was generated.", Toast.LENGTH_LONG).show()
        }
        AppPreferences.setServerPort(this, port)
        AppPreferences.setLanEnabled(this, lan)
        AppPreferences.setApiKey(this, key)
        LocalApiService.start(applicationContext, port, lan, key)
        Toast.makeText(this, "Starting web chat and API server...", Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ refreshUi() }, 300L)
        binding.root.postDelayed({ refreshUi() }, 1200L)
    }

    private fun stopServer() {
        LocalApiService.stop(applicationContext)
        binding.root.postDelayed({ refreshUi() }, 250L)
    }

    private fun refreshUi() {
        val running = LocalApiServer.isRunning()
        binding.tvServerStatus.text = if (running) {
            if (LocalApiService.active) "Running in background" else "Running"
        } else "Stopped"
        binding.btnServerToggle.text = if (running) "Stop server" else "Start server"
        binding.tvLocalAddress.text = if (running) LocalApiServer.localhostUrl() else "—"
        binding.tvLanAddress.text = if (running) LocalApiServer.lanUrl() ?: "LAN access disabled" else "—"
        binding.tvWebAddress.text = if (running) LocalApiServer.webUrl() else "—"
        binding.tvApiAddress.text = if (running) LocalApiServer.apiUrl() else "—"
        binding.tvServerModel.text = buildString {
            append(InferenceBridge.activeModelName ?: "No model loaded")
            InferenceBridge.requestedComputeUnit?.let { append(" • ").append(it.uppercase()) }
        }
        binding.btnCopyWebUrl.isEnabled = running
        binding.btnOpenWebUi.isEnabled = running
        binding.btnCopyApiUrl.isEnabled = running
    }

    private fun openWebUi() {
        if (!LocalApiServer.isRunning()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LocalApiServer.webUrl()))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No browser is available to open the web chat.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copy(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied.", Toast.LENGTH_SHORT).show()
    }

    private fun generateKey(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
