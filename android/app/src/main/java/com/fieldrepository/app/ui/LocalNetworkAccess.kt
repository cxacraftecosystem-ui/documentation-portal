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

/*
 * ── ANDROID 17'S LOCAL-NETWORK PERMISSION, AND THE ONE KIND OF BUILD IT REACHES ────────────────────
 *
 * From targetSdk 37 every connection to a LOCAL-NETWORK address — the RFC 1918 ranges, link-local,
 * the 100.64/10 carrier-NAT block, IPv6 link-local and unique-local, `.local` names — needs
 * ACCESS_LOCAL_NETWORK, a runtime permission in the Nearby devices group. Without it a TCP connect
 * does not fail; it TIMES OUT, which reads exactly like a backend that is down.
 *
 * Production never meets this: the compiled-in API base is public HTTPS through CloudFront, media is
 * public S3, and the release manifest does not declare the permission. A DEVELOPMENT build does: the
 * emulator reaches the host machine at 10.0.2.2 and a handset reaches a laptop at its LAN address
 * (android/README.md, "Run"). So the permission is declared in src/debug/AndroidManifest.xml only,
 * and this asks for it at launch in a debug build whose `apiBaseUrl` points at such an address.
 *
 * Loopback is not the local network. `adb reverse tcp:8000 tcp:8000` with
 * `apiBaseUrl=http://127.0.0.1:8000/api/` needs no permission and no prompt, on the emulator and on a
 * USB-connected handset alike, and it is the only route that works for a RELEASE-variant build
 * pointed at a local backend, since release never declares the permission.
 */

internal const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Android 17. A literal because nothing else in the app needs the VERSION_CODES name. */
internal const val LOCAL_NETWORK_PERMISSION_SINCE_SDK = 37

/**
 * Whether Android 17 treats [host] as a local-network destination.
 *
 * PURE, AND IT NEVER RESOLVES ANYTHING. Only an address LITERAL is classified, by parsing it here —
 * `InetAddress.getByName` is deliberately not used, because handed anything that is not a valid
 * literal it falls through to a DNS lookup, on whatever thread called it. So a NAME that happens to
 * resolve to a LAN address (`devbox.lan`) is answered false: the only safe answer without a lookup,
 * and the reason android/README.md says to give a development backend as an address or as loopback.
 */
internal fun isLocalNetworkHost(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (h.isEmpty()) return false
    if (h.endsWith(".local")) return true
    ipv4Octets(h)?.let { return isLocalIpv4(it) }
    if (':' in h) return isLocalIpv6(h)
    return false
}

/** The four octets of a dotted quad, or null for anything that is not exactly one. */
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
    o[0] == 10 -> true                                // 10.0.0.0/8 (and the emulator's 10.0.2.2)
    o[0] == 172 && o[1] in 16..31 -> true             // 172.16.0.0/12
    o[0] == 192 && o[1] == 168 -> true                // 192.168.0.0/16
    o[0] == 169 && o[1] == 254 -> true                // 169.254.0.0/16, link-local
    o[0] == 100 && o[1] in 64..127 -> true            // 100.64.0.0/10, carrier-grade NAT
    else -> false                                     // loopback, public and everything else
}

/**
 * An IPv6 literal, classified by its first 16 bits — which is all the two local prefixes need — or,
 * for an IPv4-mapped/embedded form (`::ffff:192.168.1.5`), by the IPv4 at its end. A zone id
 * (`fe80::1%wlan0`) is ignored. Anything that does not look like an IPv6 literal is not local.
 */
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
    return (first and 0xFFC0) == 0xFE80 ||            // fe80::/10, link-local
        (first and 0xFE00) == 0xFC00                  // fc00::/7, unique-local
}

/**
 * Whether this build, on this device, must hold ACCESS_LOCAL_NETWORK to reach [apiBaseUrl].
 * Debug only — release does not declare the permission, so asking for it there could never succeed.
 */
internal fun needsLocalNetworkPermission(debug: Boolean, sdkInt: Int, apiBaseUrl: String): Boolean {
    if (!debug || sdkInt < LOCAL_NETWORK_PERMISSION_SINCE_SDK) return false
    val host = runCatching { URI(apiBaseUrl).host }.getOrNull() ?: return false
    return isLocalNetworkHost(host)
}

/**
 * Asks once per launch, and only when [needsLocalNetworkPermission] says so. Renders nothing.
 *
 * A refusal is explained once, in words that name both ways out, and not re-asked in a loop: the
 * system stops showing a prompt the person keeps refusing anyway.
 */
@Composable
internal fun LocalNetworkAccessForDevelopmentBackend(apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL) {
    // Constant for the life of the process (the build type, the device, a compiled-in URL), so this
    // early return never changes between compositions.
    if (!needsLocalNetworkPermission(BuildConfig.DEBUG, Build.VERSION.SDK_INT, apiBaseUrl)) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            /*
             * NO `http://…/api/` IN THIS SENTENCE, AND THAT IS LOAD-BEARING. This file is in src/main, so
             * the literal ships in the RELEASE dex too, and publish-android.yml refuses any release whose
             * bytes hold a cleartext `http://<host>/api/` — it cannot tell help text from a localhost API
             * base, and must not try. The first version spelled out the loopback URL here, and every tag
             * push would have stopped at that guard. CleartextApiBaseTest holds the compiled classes to
             * the guard's own pattern on every pull request.
             */
            Toast.makeText(
                context,
                "Android 17 blocks this debug build from reaching the development backend at " +
                    "$apiBaseUrl until Nearby devices is allowed for the app. Allow it in Settings, " +
                    "or use adb reverse and point apiBaseUrl at 127.0.0.1 (android/README.md).",
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
