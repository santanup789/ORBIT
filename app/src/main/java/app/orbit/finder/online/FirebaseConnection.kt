package app.orbit.finder.online

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/** Explicit, read-only connection check. Does not collect or upload location or friend data. */
class FirebaseConnection(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var reference: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var timeout: Runnable? = null

    fun check(report: (String, Boolean) -> Unit, completed: (Boolean) -> Unit = {}) {
        close()
        val attempt = generation
        fun finish(message: String, success: Boolean = false) {
            if (generation != attempt) return
            close()
            report(message, false)
            completed(success)
        }
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context)
        if (app == null) {
            finish("Firebase configuration missing. Bluetooth pairing still works. Add the ORBIT google-services.json and rebuild.")
            return
        }
        if (app.options.projectId != "orbit-945f6") {
            finish("Wrong Firebase project configuration; connection cancelled.")
            return
        }
        report("Connecting to Firebase. No location is being shared…", true)
        timeout = Runnable { finish("Connection timed out. Check internet access and retry. No location was shared.") }
            .also { main.postDelayed(it, 25_000) }
        try {
            val auth = FirebaseAuth.getInstance(app)
            // Refresh an existing session too: a cached user alone is not proof of online connectivity.
            fun checkDatabase() {
                if (generation != attempt) return
                val db = FirebaseDatabase.getInstance(app, DATABASE_URL)
                val ref = db.getReference(".info/connected")
                reference = ref
                val observer = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.getValue(Boolean::class.java) == true) {
                            finish("Connected to ORBIT Firebase. This check does not share your location. Open a friend’s Outdoor GPS screen to start a sharing session.", true)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        finish("Firebase database check failed (${error.code}). Retry when connected.")
                    }
                }
                listener = observer
                ref.addValueEventListener(observer)
            }
            val user = auth.currentUser
            if (user != null) {
                user.getIdToken(true).addOnCompleteListener { task ->
                    if (task.isSuccessful) checkDatabase()
                    else finish("Firebase sign-in could not be refreshed. Check internet access and retry.")
                }
            } else {
                auth.signInAnonymously().addOnCompleteListener { task ->
                    if (task.isSuccessful) checkDatabase()
                    else finish("Firebase anonymous sign-in failed. Check internet access and the enabled sign-in provider.")
                }
            }
        } catch (_: Exception) {
            finish("Firebase configuration could not be used. Check the Android configuration and rebuild.")
        }
    }

    fun close() {
        generation++
        timeout?.let(main::removeCallbacks)
        timeout = null
        listener?.let { reference?.removeEventListener(it) }
        listener = null
        reference = null
    }

    companion object {
        const val DATABASE_URL = "https://orbit-945f6-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}
