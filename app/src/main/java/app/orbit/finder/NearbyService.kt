package app.orbit.finder

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat

class OrbitApplication : Application() {
    val model by lazy { OrbitViewModel(this) }
}

class PairingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("pairing", "Pairing requests and results", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("nearby", "Nearby session", NotificationManager.IMPORTANCE_LOW))
    }
    fun contentIntent(): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun show(key: String, title: String, message: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        manager.notify(key, 2, NotificationCompat.Builder(context, "pairing").setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(contentIntent()).setAutoCancel(true).setTimeoutAfter(120_000).build())
    }
    fun cancel(key: String) = manager.cancel(key, 2)
}

class NearbyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { (application as OrbitApplication).model.stopNearby(); stopSelf(); return START_NOT_STICKY }
        val notices = PairingNotifications(this)
        val stop = PendingIntent.getService(this, 1, Intent(this, NearbyService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, NotificationCompat.Builder(this, "nearby").setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle("ORBIT Nearby session").setContentText("Tap to view radio status and pairing requests.")
            .setContentIntent(notices.contentIntent()).setOngoing(true).addAction(0, "Stop Nearby", stop).build())
        return START_NOT_STICKY
    }
}
