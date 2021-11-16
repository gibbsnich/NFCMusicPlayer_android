package com.revosleap.samplemusicplayer.ui.blueprints

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.room.*
import com.revosleap.samplemusicplayer.R
import com.revosleap.samplemusicplayer.models.Song
import com.revosleap.samplemusicplayer.utils.RecyclerAdapter
import com.revosleap.samplemusicplayer.utils.Utils
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

abstract class MainActivityBluePrint : AppCompatActivity(), ActionMode.Callback, RecyclerAdapter.OnLongClick,
        RecyclerAdapter.SongsSelected, RecyclerAdapter.SongClicked {
    private var actionMode: ActionMode? = null
    private var songAdapter: RecyclerAdapter? = null
    private var deviceMusic = mutableListOf<Song>()


    lateinit var musicEntryDao: MusicEntryDao
    lateinit var dirEntryDao: DirEntryDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val db = Room.databaseBuilder(applicationContext,
                MusicEntryDatabase::class.java, "music.db").build()

        musicEntryDao = db.musicEntryDao()
        dirEntryDao = db.dirEntryDao()

        songAdapter = RecyclerAdapter()
        setViews()
    }

    override fun onSongLongClicked(position: Int) {
        if (actionMode == null) {
            actionMode = startActionMode(this)
        }
    }

    override fun onSelectSongs(selectedSongs: MutableList<Song>) {
        if (selectedSongs.isEmpty()) {
            actionMode?.finish()
            songAdapter?.removeSelection()
        } else {
            val title = "Delete ${selectedSongs.size} Songs"
            actionMode?.title = title
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        val inflater = mode?.menuInflater
        inflater?.inflate(R.menu.action_mode_menu, menu!!)
        toolbar.visibility= View.GONE
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_delete -> {
                val songs = songAdapter?.getSelectedSongs()
                songs?.forEach {
                    val file = File(it.path)
                    Utils.delete(this@MainActivityBluePrint, file)
                    songAdapter?.updateRemoved(it)
                }
                Toast.makeText(this, "Deleted ${songs?.size} Songs", Toast.LENGTH_SHORT).show()
                mode?.finish()
                songAdapter?.removeSelection()
                return true
            }

        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        songAdapter?.removeSelection()
        toolbar.visibility= View.VISIBLE
        actionMode = null
    }

    private fun setViews() {

        songAdapter?.setOnLongClick(this)
        songAdapter?.setSongsSelected(this)
        songAdapter?.setOnSongClicked(this)
        recyclerView?.apply {
            adapter = songAdapter
            layoutManager = LinearLayoutManager(this@MainActivityBluePrint)
            hasFixedSize()
        }

    }

    fun getMusic(){
        //deviceMusic.addAll(SongProvider.getAllDeviceSongs(this))
        //val songs = SongProvider.getAllDeviceSongs(this)
        deviceMusic.clear()
        GlobalScope.launch {
            deviceMusic.addAll(getAllTaggedSongs())
            withContext(Dispatchers.Main) {
                songAdapter?.addSongs(deviceMusic)
            }
        }
    }

    suspend fun getAllTaggedSongs(): MutableList<Song> {
        val songs = ArrayList<Song>()

        val entries = musicEntryDao.allEntries()
        entries!!.forEach {
            songs.add(musicEntryToSong(it))
        }

        return songs
    }


    fun musicEntryToSong(entry: MusicEntry): Song {
        return Song(entry.path.toString(), entry.entryId,0, 0, entry.path, entry.nfcKey!!, 0, "")
    }

    fun dirEntryToSong(entry: DirEntry): Song {
        return Song(entry.path.toString(), entry.entryId,0, 0, entry.path, entry.nfcKey!!, 0, "")
    }

    @Entity(tableName = "musicentry")
    data class MusicEntry(
            @PrimaryKey(autoGenerate = true)
            val entryId: Int,
            @ColumnInfo(name = "nfckey") val nfcKey: String?,
            @ColumnInfo(name = "path") val path: String?,
            @ColumnInfo(name = "position") val position: Int
    )

    @Dao
    interface MusicEntryDao {
        @Insert
        suspend fun insertEntry(entry: MusicEntry)

        @Query("SELECT * FROM musicentry WHERE nfckey = :nkey")
        fun loadMusicEntry(nkey: String): MusicEntry

        @Query("UPDATE musicentry set position = :pos WHERE nfckey = :nkey")
        fun updatePosition(nkey: String, pos: Integer)

        @Query("SELECT * FROM musicentry")
        suspend fun allEntries(): List<MusicEntry>


    }

    @Entity(tableName = "direntry")
    data class DirEntry(
            @PrimaryKey(autoGenerate = true)
            val entryId: Int,
            @ColumnInfo(name = "nfckey") val nfcKey: String?,
            @ColumnInfo(name = "path") val path: String?,
            @ColumnInfo(name = "position") val position: Int
    )

    @Dao
    interface DirEntryDao {
        @Insert
        suspend fun insertEntry(entry: DirEntry)

        @Query("SELECT * FROM direntry where nfckey = :nkey")
        fun loadEntries(nkey: String): List<DirEntry>

        @Query("UPDATE direntry SET position = 0 WHERE nfckey = :nkey")
        fun resetPositions(nkey: String)

        @Query("UPDATE direntry SET position = :pos WHERE nfckey = :nkey AND entryId = :eid")
        fun updatePosition(nkey: String, eid: Int, pos: Int)

        @Query("SELECT * from direntry WHERE nfckey = :nkey AND position != 0")
        fun getLastPosition(nkey: String): DirEntry
    }

    @Database(entities = [MusicEntry::class, DirEntry::class], version = 1)
    abstract class MusicEntryDatabase : RoomDatabase() {
        abstract fun musicEntryDao(): MusicEntryDao
        abstract fun dirEntryDao(): DirEntryDao
    }
}