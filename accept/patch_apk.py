#!/usr/bin/env python3
import glob
import sys


def one(pattern):
    hits = glob.glob(pattern, recursive=True)
    if len(hits) != 1:
        sys.exit(f"expected one file for {pattern}, got {hits}")
    return hits[0]


def swap(path, old, new):
    text = open(path, encoding="utf-8").read()
    if text.count(old) != 1:
        sys.exit(f"{path}: anchor found {text.count(old)} times: {old[:80]!r}")
    open(path, "w", encoding="utf-8").write(text.replace(old, new))
    print(f"patched {path}")


api = one("android/app/src/main/java/com/*/*/data/ApiClient.kt")
text = open(api, encoding="utf-8").read()
if "HttpLoggingInterceptor().apply {" in text:
    swap(api, "HttpLoggingInterceptor().apply {", 'HttpLoggingInterceptor { android.util.Log.i("OkHttp", it) }.apply {')
    swap(api, "level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE", "level = HttpLoggingInterceptor.Level.BASIC")
else:
    swap(api, "if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE", "HttpLoggingInterceptor.Level.BASIC")
    swap(api, "logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT", 'logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger { android.util.Log.i("OkHttp", it) }')

engine = one("android/app/src/main/java/com/*/*/explorer/data/ExplorerEngine.kt")
swap(
    engine,
    "        val url = item.url\n        val mediaId = item.mediaId\n        if (url.isNullOrBlank()) {",
    "        val url = item.url\n        val mediaId = item.mediaId\n"
    '        val acceptVictim = runCatching { ProcessBuilder("getprop", "debug.accept.victim").start().inputStream.bufferedReader().use { it.readText().trim() } }.getOrDefault("")\n'
    "        if (acceptVictim.isNotEmpty() && mediaId == acceptVictim) {\n"
    '            android.util.Log.i("ACCEPT", "VICTIM $mediaId requested as accept-0016-missing")\n'
    "            kotlinx.coroutines.delay(20000)\n"
    '            return ExplorerTransfer.fetchTo(temp, resumable = resumable, keep = keep, backoffMs = backoffMs, open = { offset -> openApi(api.media("accept-0016-missing", format, null, ExplorerTransfer.range(offset))) }, onBytes = onBytes)\n'
    "        }\n"
    "        if (url.isNullOrBlank()) {",
)
