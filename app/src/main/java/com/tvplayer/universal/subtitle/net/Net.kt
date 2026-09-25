package com.tvplayer.universal.subtitle.net

import android.util.Base64
import android.util.Log
import com.tvplayer.universal.App
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .apply { runCatching { useBundledTrustAnchors() } }
            .build()
    }

    /**
     * 字幕站的证书链都落在新根上（assrt 到 ISRG Root X2，OpenSubtitles 到 GTS Root R4），
     * 而这台 Android 5.1 电视的系统 CA 目录（/system/etc/security/cacerts 下）两条都没有 ——
     * 在真机上按关键字 grep 整个目录，ISRG 与 Google Trust 均无命中。
     * 结果是搜索请求全部死在 TLS 握手（CertPathValidatorException: Trust anchor not found），
     * 表现出来却只是"未找到匹配的中文字幕"。所以自带一份现代根证书，跟系统锚点合并使用，
     * 而不是放宽校验 —— 主机名验证照旧。
     */
    private fun OkHttpClient.Builder.useBundledTrustAnchors() {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        var fromSystem = 0
        var fromBundle = 0
        runCatching {
            val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in system.aliases()) {
                val cert = system.getCertificate(alias) ?: continue
                anchors.setCertificateEntry("sys$alias", cert)
                fromSystem++
            }
        }
        val cf = CertificateFactory.getInstance("X.509")
        App.context.assets.open("ca-roots.pem").use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            for (m in PEM.findAll(text)) {
                val der = Base64.decode(m.groupValues[1].replace("\\s".toRegex(), ""), Base64.DEFAULT)
                runCatching {
                    anchors.setCertificateEntry("bundle$fromBundle", cf.generateCertificate(ByteArrayInputStream(der)))
                    fromBundle++
                }
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(anchors)
        val trustManager = factory.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            ?: return
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        sslSocketFactory(sslContext.socketFactory, trustManager)
        Log.i("Net", "字幕 TLS 锚点：系统 $fromSystem 条 + 自带 $fromBundle 条")
    }

    private val PEM = Regex("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)

    fun get(url: String): ByteArray = get(url, emptyMap())

    fun get(url: String, headers: Map<String, String>): ByteArray =
        client.newCall(withHeaders(Request.Builder().url(url), headers).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(httpFailure(resp.code, resp.body?.string()))
                resp.body!!.bytes()
            }

    /**
     * 非 2xx 时把响应体里的原因带进异常消息。字幕站的拒绝理由各不相同
     * （OpenSubtitles 未授权时是 "You cannot consume this service"），
     * 只有 HTTP 状态码的话，界面上那句"未找到"永远说不清是为什么。
     */
    fun httpFailure(code: Int, body: String?): String {
        val reason = body?.take(300)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return "HTTP $code" + (if (reason.isBlank()) "" else " $reason")
    }

    fun postJson(url: String, json: String, headers: Map<String, String>): ByteArray =
        client.newCall(
            withHeaders(
                Request.Builder().url(url),
                headers + mapOf("Content-Type" to "application/json")
            )
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(httpFailure(resp.code, resp.body?.string()))
            resp.body!!.bytes()
        }

    private fun withHeaders(rb: Request.Builder, headers: Map<String, String>): Request.Builder {
        var builder = rb
        headers.forEach { (k, v) -> builder = builder.header(k, v) }
        return builder
    }

    fun getForm(url: String, params: Map<String, String>): ByteArray =
        getForm(url, params, emptyMap())

    fun getForm(url: String, params: Map<String, String>, headers: Map<String, String>): ByteArray {
        val form = StringBuilder()
        params.forEach { (k, v) ->
            if (form.isNotEmpty()) form.append('&')
            form.append(java.net.URLEncoder.encode(k, "UTF-8"))
                .append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val full = if (url.contains('?')) "$url&$form" else "$url?$form"
        return get(full, headers)
    }

    fun postForm(url: String, params: Map<String, String>): ByteArray {
        val body = params.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                java.net.URLEncoder.encode(v, "UTF-8")
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        return client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} $url")
            resp.body!!.bytes()
        }
    }
}
