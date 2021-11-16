package com.revosleap.samplemusicplayer.ui.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.nfc.*
import android.nfc.NfcAdapter.ReaderCallback
import android.nfc.tech.*
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.*
import com.revosleap.samplemusicplayer.R
import com.revosleap.samplemusicplayer.models.Song
import com.revosleap.samplemusicplayer.playback.MusicNotificationManager
import com.revosleap.samplemusicplayer.playback.MusicService
import com.revosleap.samplemusicplayer.playback.PlaybackInfoListener
import com.revosleap.samplemusicplayer.playback.PlayerAdapter
import com.revosleap.samplemusicplayer.ui.blueprints.MainActivityBluePrint
import com.revosleap.samplemusicplayer.utils.EqualizerUtils
import com.revosleap.samplemusicplayer.utils.RecyclerAdapter
import com.revosleap.samplemusicplayer.utils.Utils
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.controls.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import android.content.ContentUris

import android.os.Build
import kotlinx.coroutines.withContext


class MainActivity : MainActivityBluePrint(), View.OnClickListener, RecyclerAdapter.SongClicked, ReaderCallback {

    private var seekBar: SeekBar? = null
    private var playPause: ImageButton? = null
    private var next: ImageButton? = null
    private var previous: ImageButton? = null
    private var songTitle: TextView? = null
    private var mMusicService: MusicService? = null
    private var mIsBound: Boolean? = null
    private var mPlayerAdapter: PlayerAdapter? = null
    private var mUserIsSeeking = false
    private var mPlaybackListener: PlaybackListener? = null
    private var deviceSongs: MutableList<Song>? = null
    private var mMusicNotificationManager: MusicNotificationManager? = null

    private var nfcAdapter: NfcAdapter? = null

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {

            mMusicService = (iBinder as MusicService.LocalBinder).instance
            mPlayerAdapter = mMusicService!!.mediaPlayerHolder
            mPlayerAdapter!!.setDaos(musicEntryDao, dirEntryDao)

            mMusicNotificationManager = mMusicService!!.musicNotificationManager

            if (mPlaybackListener == null) {
                mPlaybackListener = PlaybackListener()
                mPlayerAdapter!!.setPlaybackInfoListener(mPlaybackListener!!)
            }
            if (mPlayerAdapter != null && mPlayerAdapter!!.isPlaying()) {

                restorePlayerStatus()
            }
            checkReadStoragePermissions()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            mMusicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        doBindService()
        setViews()
        initializeSeekBar()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val options = Bundle()
        // Work around for some broken Nfc firmware implementations that poll the card too fast
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        // Enable ReaderMode for all types of card and disable platform sounds
        nfcAdapter!!.enableReaderMode(this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NFC_BARCODE or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                        or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,

                options)
    }

    override fun onPause() {
        super.onPause()
        doUnbindService()
        if (mPlayerAdapter != null && mPlayerAdapter!!.isMediaPlayer()) {
            mPlayerAdapter!!.onPauseActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        doBindService()
        if (mPlayerAdapter != null && mPlayerAdapter!!.isPlaying()) {

            restorePlayerStatus()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            finish()
        } else getMusic()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_equalizer -> {
                EqualizerUtils.openEqualizer(this, mPlayerAdapter?.getMediaPlayer())
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setViews() {
        playPause = findViewById(R.id.buttonPlayPause)
        next = findViewById(R.id.buttonNext)
        previous = findViewById(R.id.buttonPrevious)
        seekBar = findViewById(R.id.seekBar)
        songTitle = findViewById(R.id.songTitle)
        playPause!!.setOnClickListener(this)
        next!!.setOnClickListener(this)
        previous!!.setOnClickListener(this)
        GlobalScope.launch {
            deviceSongs = getAllTaggedSongs()
        }
        //deviceSongs = SongProvider.getAllDeviceSongs(this)
        setSupportActionBar(toolbar)
    }

    private fun checkReadStoragePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else getMusic()
    }

    private fun updatePlayingInfo(restore: Boolean, startPlay: Boolean) {

        if (startPlay) {
            mPlayerAdapter!!.getMediaPlayer()?.start()
            Handler().postDelayed({
                mMusicService!!.startForeground(MusicNotificationManager.NOTIFICATION_ID,
                        mMusicNotificationManager!!.createNotification())
            }, 200)
        }

        val selectedSong = mPlayerAdapter!!.getCurrentSong()

        songTitle?.text = selectedSong?.title
        val duration = selectedSong?.duration
        //seekBar?.max = duration!!
        seekBar?.max = mPlayerAdapter!!.getCurrentDuration()
        imageViewControl?.setImageBitmap(Utils.songArt(selectedSong?.path!!, this@MainActivity))

        if (restore) {

            seekBar!!.progress = mPlayerAdapter!!.getPlayerPosition()
            updatePlayingStatus()

            Handler().postDelayed({
                //stop foreground if coming from pause state
                if (mMusicService!!.isRestoredFromPause) {
                    mMusicService!!.stopForeground(false)
                    mMusicService!!.musicNotificationManager!!.notificationManager
                            .notify(MusicNotificationManager.NOTIFICATION_ID,
                                    mMusicService!!.musicNotificationManager!!.notificationBuilder!!.build())
                    mMusicService!!.isRestoredFromPause = false
                }
            }, 200)
        }
    }

    private fun updatePlayingStatus() {
        val drawable = if (mPlayerAdapter!!.getState() != PlaybackInfoListener.State.PAUSED)
            R.drawable.ic_pause
        else
            R.drawable.ic_play
        playPause!!.post { playPause!!.setImageResource(drawable) }
    }

    private fun restorePlayerStatus() {
        seekBar!!.isEnabled = mPlayerAdapter!!.isMediaPlayer()

        //if we are playing and the activity was restarted
        //update the controls panel
        if (mPlayerAdapter != null && mPlayerAdapter!!.isMediaPlayer()) {

            mPlayerAdapter!!.onResumeActivity()
            updatePlayingInfo(true, false)
        }
    }

    private fun doBindService() {
        // Establish a connection with the service.  We use an explicit
        // class name because we want a specific service implementation that
        // we know will be running in our own process (and thus won't be
        // supporting component replacement by other applications).
        bindService(Intent(this,
                MusicService::class.java), mConnection, Context.BIND_AUTO_CREATE)
        mIsBound = true

        val startNotStickyIntent = Intent(this, MusicService::class.java)
        startService(startNotStickyIntent)
    }

    private fun doUnbindService() {
        if (mIsBound!!) {
            // Detach our existing connection.
            unbindService(mConnection)
            mIsBound = false
        }
    }

    private fun onSongSelected(song: Song, songs: List<Song>) {
        if (!seekBar!!.isEnabled) {
            seekBar!!.isEnabled = true
        }
        try {
            mPlayerAdapter!!.setCurrentSong(song, songs)
            //mPlayerAdapter!!.initMediaPlayer()
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun skipPrev() {
        if (checkIsPlayer()) {
            mPlayerAdapter!!.instantReset()
        }
    }

    private fun resumeOrPause() {
        if (checkIsPlayer()) {
            mPlayerAdapter!!.resumeOrPause()
        } else {
            GlobalScope.launch {
                val songs = getAllTaggedSongs()
                withContext(Dispatchers.Main) {
                    if (songs.isNotEmpty()) {
                        onSongSelected(songs[0], songs)
                    }
                }
            }
//            val songs = SongProvider.getAllDeviceSongs(this)
//            if (songs.isNotEmpty()) {
//                onSongSelected(songs[0], songs)
//
//            }
        }
    }

    private fun skipNext() {
        if (checkIsPlayer()) {
            mPlayerAdapter!!.skip(true)
        }
    }

    private fun checkIsPlayer(): Boolean {
        return mPlayerAdapter!!.isMediaPlayer()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.buttonPlayPause -> {
                resumeOrPause()
            }
            R.id.buttonNext -> {
                skipNext()
            }
            R.id.buttonPrevious -> {
                skipPrev()
            }
        }
    }

    private fun initializeSeekBar() {
        seekBar!!.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    var userSelectedPosition = 0

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        mUserIsSeeking = true
                    }

                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {

                        if (fromUser) {
                            userSelectedPosition = progress

                        }

                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {

                        if (mUserIsSeeking) {

                        }
                        mUserIsSeeking = false
                        mPlayerAdapter!!.seekTo(userSelectedPosition)
                    }
                })
    }

    override fun onSongClicked(song: Song) {
        val curSongs = ArrayList<Song>()
        GlobalScope.launch(Dispatchers.IO) {
            val nfctag = song.albumName

            val dirEntries = dirEntryDao.loadEntries(nfctag)
            if (dirEntries.isEmpty()) {
                val entry = musicEntryDao.loadMusicEntry(nfctag)
                curSongs.add(musicEntryToSong(entry))
            } else {
                dirEntries.forEach {
                    curSongs.add(dirEntryToSong(it))
                }
            }
            onSongSelected(curSongs[0], curSongs)
        }


        //onSongSelected(song, deviceSongs!!)
    }

    internal inner class PlaybackListener : PlaybackInfoListener() {

        override fun onPositionChanged(position: Int) {
            if (!mUserIsSeeking) {
                seekBar!!.progress = position
            }
        }

        override fun onStateChanged(@State state: Int) {

            updatePlayingStatus()
            if (mPlayerAdapter!!.getState() != State.PAUSED
                    && mPlayerAdapter!!.getState() != State.PAUSED) {
                updatePlayingInfo(false, true)
            }
        }

        override fun onPlaybackCompleted() {
            //After playback is complete
            //todo update position (= 0) in MusicEntry DB for current track
        }
    }

    private var currentTagPayload: String? = null

    override fun onTagDiscovered(p0: Tag?) {

        currentTagPayload = getPayloadFromTag(p0)
        if (currentTagPayload != null) {
            var mEntry = musicEntryDao.loadMusicEntry(currentTagPayload!!)
            if (mEntry != null) {
                if (mEntry.path.equals("STOP")) {
                    if (mPlayerAdapter!!.isPlaying()) {
                        //todo also store current track index if a directory is currently playing
                        //mPlayerAdapter!!.getPlayerPosition()

                        mPlayerAdapter!!.storeCurrentPositionAndIndex()
                        mPlayerAdapter!!.resumeOrPause()
                    }
                } else if (mEntry.path.equals("PLAY")) {
                    if (mPlayerAdapter!!.isPlaying())
                        mPlayerAdapter!!.resumeOrPause()
                } else {
                    onSongClicked(Song("", 0, 0, 0, null, currentTagPayload!!, 0, ""))
                }
            } else {
                showNewTagPrompt()
            }
        } else {
            this@MainActivity.runOnUiThread {
                toast("Kann NFC-Tag nicht lesen!")
            }
        }
    }

    var newTagPrompt: AlertDialog? = null

    private fun showNewTagPrompt() {
        val view = LayoutInflater.from(this).inflate(R.layout.new_tag_fragment, null)
        view.findViewById<Button>(R.id.pick_dir).setOnClickListener {
            //toast("clicked pick directory")
            pickDirectory()
            newTagPrompt!!.dismiss()
        }
        view.findViewById<Button>(R.id.pick_file).setOnClickListener {
            //toast("clicked pick file")
            pickFile()
            newTagPrompt!!.dismiss()
        }
        view.findViewById<Button>(R.id.action_start).setOnClickListener {
            //toast("clicked START button")
            val entry = MusicEntry(0, currentTagPayload, "START", 0)
            GlobalScope.launch(Dispatchers.IO) {
                musicEntryDao.insertEntry(entry)
            }
            newTagPrompt!!.dismiss()
        }
        view.findViewById<Button>(R.id.action_stop).setOnClickListener {
            //toast("clicked STOP button")
            val entry = MusicEntry(0, currentTagPayload, "STOP", 0)
            GlobalScope.launch(Dispatchers.IO) {
                musicEntryDao.insertEntry(entry)
            }
            newTagPrompt!!.dismiss()
        }

        this@MainActivity.runOnUiThread {
            newTagPrompt = AlertDialog.Builder(this).setView(view).create()
            newTagPrompt!!.show()
        }
    }

    val PICK_FILE = 2
    val PICK_DIR = 3

    private fun pickDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_DIR)
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "audio/*"
        startActivityForResult(intent, PICK_FILE)
    }

    fun getPathFromUri(context: Context, uri: Uri): String? {
        val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":").toTypedArray()
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }

                // TODO handle non-primary volumes
            } else if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id))
                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":").toTypedArray()
                val type = split[0]
                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(
                        split[1]
                )
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {

            // Return the remote address
            return if (isGooglePhotosUri(uri)) uri.lastPathSegment else getDataColumn(context, uri, null, null)
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    fun getDataColumn(context: Context, uri: Uri?, selection: String?,
                      selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(
                column,
                MediaStore.Audio.AudioColumns.DURATION
        )
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs,
                    null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                val uri = cursor.getString(index)
                return uri
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    @SuppressLint("MissingSuperCall")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if ((requestCode == PICK_FILE || requestCode == PICK_DIR) && resultCode == Activity.RESULT_OK) {
            resultData?.data?.also { uri ->
                var filePath: String? = null
                var mediaFiles: Array<File>? = null
                if (requestCode == PICK_DIR) {

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri,
                            DocumentsContract.getTreeDocumentId(uri))

                    filePath = getPathFromUri(this, docUri).toString()

                    mediaFiles = File(filePath.toString()).listFiles { file ->
                        file.name.lowercase().endsWith(".mp3")
                    }
                } else {
                    if (uri != null && "content" == uri.getScheme()) {
                        filePath = getPathFromUri(this, uri).toString()
                    } else {
                        filePath = uri.getPath()
                    }
                }

                val entry = MusicEntry(0, currentTagPayload, filePath.toString(), 0)
                GlobalScope.launch(Dispatchers.IO) {
                    musicEntryDao.insertEntry(entry)

                    if (mediaFiles != null) {
                        mediaFiles.forEach {
                            dirEntryDao.insertEntry(DirEntry(0, currentTagPayload, it.absolutePath, 0))
                        }
                    }
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun byteArrayToHexString(bytes: ByteArray): String? {
        val hexArray = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
        val hexChars = CharArray(bytes.size * 2)
        var v: Int
        for (j in bytes.indices) {
            v = (bytes[j].toInt() and 0xFF.toInt())
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun getPayloadFromTag(tag: Tag?): String? {

        return byteArrayToHexString(tag!!.id)

        /*
        var value: String? = null

        value = byteArrayToHexString(tag!!.id)


        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            nfcA.connect()

            val sak = nfcA.sak
            value = String(nfcA.atqa)
            nfcA.close()
        }

        val nfcB = NfcB.get(tag)
        if (nfcB != null) {
            value = String(nfcB.applicationData)
        }

        val nfcF = NfcF.get(tag)
        if (nfcF != null) {
            value = String(nfcF.systemCode)
        }

        val nfcV = NfcV.get(tag)
        if (nfcV != null) {
            value = nfcV.toString()
        }

        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            value = String(isoDep.hiLayerResponse)
        }

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                val ndefMessage = ndef.ndefMessage
                for (ndefRecord in ndefMessage.records) {
                    value = String(ndefRecord.payload)

                }
                //known payload:
                //  a) execute action (stop, start, louder, ...)
                //  b) execute track
                //unknown:
                // a) register action
                // b) select track
            } catch (e: FormatException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return value

         */
    }
}
