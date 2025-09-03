package com.joaolino.sharelink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit

class MainActivity : AppCompatActivity() {
    private val serverUrl = /*"http://10.0.0.5:8085/api/share-link"*/ "http://joaolino.com:8085/api/share-link"
    private val submitUrl = /*"http://10.0.0.5:8085/api/submit-selections"*/ "http://joaolino.com:8085/api/submit-selections"
    private lateinit var recyclerView: RecyclerView
    private lateinit var submitButton: Button
    private var trackList: List<Track> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.track_list)
        submitButton = findViewById(R.id.submit_button)
        recyclerView.layoutManager = LinearLayoutManager(this)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                if (sharedText.contains("youtube.com") || sharedText.contains("youtu.be") || sharedText.contains("spotify.com")) {
                    sendLinkToServer(sharedText)
                } else {
                    Toast.makeText(this, "Unsupported link type", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } ?: run {
                Toast.makeText(this, "No valid link found", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            Toast.makeText(this, "Invalid action", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun sendLinkToServer(link: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15*60, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build()
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val jsonString = "{\"link\":\"$link\"}"
                val body = jsonString.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val jsonArray = JSONArray(responseBody)
                        trackList = parseTracks(jsonArray)
                        val adapter = TrackAdapter(trackList)
                        recyclerView.adapter = adapter
                        submitButton.setOnClickListener {
                            submitSelections(adapter.getSelected())
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to get metadata: ${response.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun parseTracks(jsonArray: JSONArray): List<Track> {
        val tracks = mutableListOf<Track>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            val metadata = json.getJSONObject("metadata")
            val md = Track.Metadata(
                metadata.getString("title"),
                metadata.getString("artist"),
                metadata.getString("album"),
                metadata.getString("genre"),
                metadata.getString("year")
            )
            val variantsArray = json.getJSONArray("variants")
            val variants = mutableListOf<Track.Variant>()
            for (j in 0 until variantsArray.length()) {
                val varJson = variantsArray.getJSONObject(j)
                variants.add(
                    Track.Variant(
                        varJson.getString("id"),
                        varJson.getString("url"),
                        varJson.getString("title"),
                        varJson.getString("duration"),
                        varJson.getLong("views"),
                        varJson.getString("uploader"),
                        varJson.getString("uploadDate")
                    )
                )
            }
            tracks.add(Track(md, variants))
        }
        return tracks
    }

    private fun submitSelections(selected: List<Selection>) {
        if (selected.isEmpty()) {
            Toast.makeText(this, "No tracks selected", Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val selectionsJson = JSONArray()
                for (sel in selected) {
                    val selJson = JSONObject()
                    val mdJson = JSONObject()
                    mdJson.put("title", sel.metadata.title)
                    mdJson.put("artist", sel.metadata.artist)
                    mdJson.put("album", sel.metadata.album)
                    mdJson.put("genre", sel.metadata.genre)
                    mdJson.put("year", sel.metadata.year)
                    selJson.put("metadata", mdJson)
                    val varJson = JSONObject()
                    varJson.put("id", sel.variant.id)
                    varJson.put("url", sel.variant.url)
                    varJson.put("title", sel.variant.title)
                    varJson.put("duration", sel.variant.duration)
                    varJson.put("views", sel.variant.views)
                    varJson.put("uploader", sel.variant.uploader)
                    varJson.put("uploadDate", sel.variant.uploadDate)
                    selJson.put("variant", varJson)
                    selectionsJson.put(selJson)
                }
                val jsonObj = JSONObject().put("selections", selectionsJson)
                val body = jsonObj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(submitUrl)
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val respJson = JSONObject(response.body?.string())
                        val taskId = respJson.getString("taskId")
                        val serviceIntent = Intent(this@MainActivity, ProgressService::class.java)
                        serviceIntent.putExtra("taskId", taskId)
                        startService(serviceIntent)
                        Toast.makeText(this@MainActivity, "Selections submitted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to submit: ${response.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

data class Track(
    val metadata: Metadata,
    val variants: List<Variant>,
    var download: Boolean = true,
    var selectedVariantIndex: Int = 0
) {
    data class Metadata(
        val title: String,
        val artist: String,
        val album: String,
        val genre: String,
        val year: String
    )

    data class Variant(
        val id: String,
        val url: String,
        val title: String,
        val duration: String,
        val views: Long,
        val uploader: String,
        val uploadDate: String
    )
}

data class Selection(
    val metadata: Track.Metadata,
    val variant: Track.Variant
)

class TrackAdapter(private val tracks: List<Track>) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val trackCheckbox: CheckBox = itemView.findViewById(R.id.track_checkbox)
        val trackTitle: TextView = itemView.findViewById(R.id.track_title)
        val variantsGroup: RadioGroup = itemView.findViewById(R.id.variants_group)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.trackTitle.text = "${track.metadata.artist} - ${track.metadata.title}"
        holder.trackCheckbox.isChecked = track.download
        holder.trackCheckbox.setOnCheckedChangeListener { _, isChecked ->
            track.download = isChecked
        }
        holder.variantsGroup.removeAllViews()
        track.variants.forEachIndexed { index, variant ->
            val radioButton = RadioButton(holder.itemView.context)
            val dur = variant.duration.toInt()
            val durationStr = String.format("%d:%02d", dur / 60, dur % 60)
            radioButton.text = "${variant.title} by ${variant.uploader} ($durationStr, ${variant.views} views)"
            radioButton.id = View.generateViewId()
            holder.variantsGroup.addView(radioButton)
            if (index == track.selectedVariantIndex) {
                radioButton.isChecked = true
            }
        }
        holder.variantsGroup.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                if (group.getChildAt(i).id == checkedId) {
                    track.selectedVariantIndex = i
                    break
                }
            }
        }
    }

    override fun getItemCount(): Int = tracks.size

    fun getSelected(): List<Selection> {
        return tracks.filter { it.download }.map { Selection(it.metadata, it.variants[it.selectedVariantIndex]) }
    }
}

class ProgressService : android.app.Service() {
    private var webSocket: WebSocket? = null
    private val notificationId = 1
    private var taskId: String? = null
    private val wsUrl = /*"ws://10.0.0.5:8085/ws/progress"*/ "ws://joaolino.com:8085/ws/progress"

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        taskId = intent?.getStringExtra("taskId")
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "progress_channel")
            .setContentTitle("Music Processing")
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(notificationId, notification)
        connectWebSocket()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "progress_channel",
                "Progress Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("subscribe:$taskId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("progress:")) {
                    val parts = text.split(" ")
                    val perc = parts[0].substring(9)
                    val trackInfo = parts.getOrNull(1) ?: ""
                    updateNotification("Downloading $trackInfo: $perc%")
                } else if (text.startsWith("done:")) {
                    val status = text.substring(5)
                    updateNotification("Finished: $status")
                    Handler(Looper.getMainLooper()).postDelayed({
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }, 5000)
                } else {
                    updateNotification(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                stopSelf()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateNotification("Connection failed: ${t.message}")
                stopSelf()
            }
        })
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "progress_channel")
            .setContentTitle("Music Processing")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Done")
        super.onDestroy()
    }
}