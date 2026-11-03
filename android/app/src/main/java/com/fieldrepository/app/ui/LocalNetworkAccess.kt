package com.fieldrepository.app.ui

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.fieldrepository.app.BuildConfig
import java.net.URI

internal const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

internal const val LOCAL_NETWORK_PERMISSION_SINCE_SDK = 37

internal fun isLocalNetworkHost(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (h.isEmpty()) return false
    if (h.endsWith(".local")) return true
    ipv4Octets(h)?.let { return isLocalIpv4(it) }
    if (':' in h) return isLocalIpv6(h)
    return false
}

private fun ipv4Octets(text: String): IntArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        val value = part.toInt()
        if (value > 255) return null
        octets[i] = value
    }
    return octets
}

private fun isLocalIpv4(o: IntArray): Boolean = when {
    o[0] == 10 -> true
    o[0] == 172 && o[1] in 16..31 -> true
    o[0] == 192 && o[1] == 168 -> true
    o[0] == 169 && o[1] == 254 -> true
    o[0] == 100 && o[1] in 64..127 -> true
    else -> false
}

private fun isLocalIpv6(text: String): Boolean {
    val address = text.substringBefore('%')
    val hex = "0123456789abcdef"
    if (address.any { it !in hex && it != ':' && it != '.' }) return false
    if ('.' in address) {
        return ipv4Octets(address.substringAfterLast(':'))?.let { isLocalIpv4(it) } ?: false
    }
    val firstGroup = address.substringBefore(':')
    if (firstGroup.length > 4) return false
    val first = if (firstGroup.isEmpty()) 0 else firstGroup.toInt(16)
    return (first and 0xFFC0) == 0xFE80 ||
        (first and 0xFE00) == 0xFC00
}

internal fun needsLocalNetworkPermission(debug: Boolean, sdkInt: Int, apiBaseUrl: String): Boolean {
    if (!debug || sdkInt < LOCAL_NETWORK_PERMISSION_SINCE_SDK) return false
    val host = runCatching { URI(apiBaseUrl).host }.getOrNull() ?: return false
    return isLocalNetworkHost(host)
}

@Composable
internal fun LocalNetworkAccessForDevelopmentBackend(apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL) {
    if (!needsLocalNetworkPermission(BuildConfig.DEBUG, Build.VERSION.SDK_INT, apiBaseUrl)) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Allow Nearby devices in Settings to reach $apiBaseUrl, or use adb reverse to 127.0.0.1 (see android/README.md).",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(ACCESS_LOCAL_NETWORK)
        }
    }
}
