package com.example.data.repository
 
import com.example.data.db.NoteDao
import com.example.data.model.Note
import kotlinx.coroutines.flow.Flow
 
class NoteRepository(
    private val noteDao: NoteDao
) {
    val allNotes: Flow<List<Note>>
        get() = noteDao.getAllNotes()
 
    val activeNotes: Flow<List<Note>>
        get() = noteDao.getActiveNotes()
 
    val archivedNotes: Flow<List<Note>>
        get() = noteDao.getArchivedNotes()
 
    val trashedNotes: Flow<List<Note>>
        get() = noteDao.getTrashedNotes()
 
    fun getNote(id: Long): Flow<Note?> =
        noteDao.getNoteById(id)
 
    suspend fun getNoteOnce(id: Long): Note? =
        noteDao.getNoteByIdOnce(id)
 
    suspend fun getLatestActiveNote(): Note? =
        noteDao.getLatestActiveNote()
 
    suspend fun getLatestNote(): Note? =
        noteDao.getLatestNote()
 
    suspend fun getDailyJournalNote(dateStr: String): Note? =
        noteDao.getDailyJournalNote(dateStr)
 
    fun searchNotes(query: String): Flow<List<Note>> =
        noteDao.searchNotes(query)
 
    suspend fun insert(note: Note): Long =
        noteDao.insert(note)
 
    suspend fun insertAll(notes: List<Note>): List<Long> =
        noteDao.insertAll(notes)
 
    suspend fun update(note: Note, writeContent: Boolean = true) {
        noteDao.update(note)
    }
 
    suspend fun updateAll(notes: List<Note>, writeContent: Boolean = true) {
        noteDao.updateAll(notes)
    }
 
    suspend fun delete(note: Note) {
        noteDao.delete(note)
    }
 
    suspend fun deleteAll(notes: List<Note>) {
        noteDao.deleteAll(notes)
    }
 
    suspend fun deleteById(id: Long) {
        noteDao.deleteById(id)
    }
 
    suspend fun getAllNotesList(): List<Note> =
        noteDao.getAllNotesList()
 
    suspend fun deleteAllNotes(): Int =
        noteDao.deleteAllNotes()
 
    suspend fun autoCleanTrash(olderThanTimestamp: Long): Int =
        noteDao.autoCleanTrash(olderThanTimestamp)
 
    suspend fun emptyTrash(): Int =
        noteDao.emptyTrash()
}
