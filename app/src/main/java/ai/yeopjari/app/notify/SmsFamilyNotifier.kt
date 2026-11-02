package ai.yeopjari.app.notify

import ai.yeopjari.app.ports.FamilyNotifier
import ai.yeopjari.core.FamilyNotice
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 운전자가 등록한 가족 번호로 문자를 보낸다. 번호가 없거나, 문자 권한이 없거나, 이 기기에 문자 기능이 없으면
 * 보내지 않고 false 를 돌려준다(그때는 화면 기록에만 남긴다). 긴 문자는 여러 통으로 나눠 보낸다.
 *
 * "보냈다"는 통신사로 실제로 나갔을 때(모든 조각의 전송 결과가 RESULT_OK)만 true 다. 전송 결과가
 * [sentTimeoutMs] 안에 오지 않거나 하나라도 실패하면 false 로, 앱은 "보냈다"고 말하지 않는다.
 */
class SmsFamilyNotifier(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val sentTimeoutMs: Long = 15_000,
) : FamilyNotifier {
    override val phone: String?
        get() = FamilyNotice.normalizePhone(prefs.getString(KEY_PHONE, null))

    override fun setPhone(input: String): String? {
        val phone = FamilyNotice.normalizePhone(input)
        prefs.edit().apply { if (phone == null) remove(KEY_PHONE) else putString(KEY_PHONE, phone) }.apply()
        return phone
    }

    override suspend fun send(text: String): Boolean {
        val to = phone ?: return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        val sms = smsManager() ?: return false
        val parts = runCatching { sms.divideMessage(text) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val action = "$ACTION_SENT.${UUID.randomUUID()}"
        val results = CompletableDeferred<Boolean>()
        var pending = parts.size
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) results.complete(false)
                else if (--pending == 0) results.complete(true)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(
                    context, i, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            })
            val requested = runCatching {
                if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, sent, null)
                else sms.sendTextMessage(to, null, text, sent[0], null)
            }.isSuccess
            if (!requested) return false
            return withTimeoutOrNull(sentTimeoutMs) { results.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    }.getOrNull()

    private companion object {
        const val KEY_PHONE = "family_phone"
        const val ACTION_SENT = "ai.yeopjari.app.SMS_SENT"
    }
}
