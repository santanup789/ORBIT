package app.orbit.finder

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import com.google.firebase.FirebaseApp
import app.orbit.finder.online.FirebaseConnection
import org.json.JSONObject

/** Read-only attestation test: never logs token values or collects coordinates. */
object AppCheckInstrumentation {
    fun run(test: Instrumentation) {
        val result = Bundle()
        try {
            val token = Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(true), 60, TimeUnit.SECONDS)
            check(token.token.isNotBlank())
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS).user!!
            val idToken = Tasks.await(user.getIdToken(true), 30, TimeUnit.SECONDS).token!!
            fun request(url: String, attested: Boolean, body: String? = null): Int {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000; connection.readTimeout = 15000
                    if (attested) connection.setRequestProperty("X-Firebase-AppCheck", token.token)
                    if (body != null) {
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toByteArray()) }
                    }
                    return connection.responseCode
                } finally { connection.disconnect() }
            }
            // Read only this test installation's own empty consent path. No location data.
            val databaseUrl = "${FirebaseConnection.DATABASE_URL}/consents/${user.uid}/orbit-security-probe.json?auth=$idToken"
            val databaseAllowed = request(databaseUrl, true)
            val databaseDenied = request(databaseUrl, false)
            check(databaseAllowed == 200 && databaseDenied in listOf(401, 403)) {
                "Database enforcement not verified: attested=$databaseAllowed unattested=$databaseDenied"
            }
            val lookupUrl = "https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FirebaseApp.getInstance().options.apiKey}"
            val body = JSONObject().put("idToken", idToken).toString()
            val authAllowed = request(lookupUrl, true, body)
            val authDenied = request(lookupUrl, false, body)
            check(authAllowed == 200 && authDenied in listOf(400, 401, 403)) {
                "Authentication enforcement not verified: attested=$authAllowed unattested=$authDenied"
            }
            result.putString("stream", "PASS: release Play Integrity attestation; attested Authentication and Database reads succeed, identical unattested reads denied. No coordinates collected or uploaded; token values not exported.\n")
            test.finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: App Check/authentication: ${e.javaClass.simpleName}: ${e.message}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
