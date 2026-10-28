package com.example.data.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap

data class FastEntry(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val lastModified: Long,
    val size: Long,
    val isDirectory: Boolean,
    val uri: Uri
) {
    val isFile: Boolean get() = !isDirectory

    fun toDocumentFile(context: Context, treeUri: Uri? = null): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
    }

    fun toDirectoryDocumentFile(context: Context, treeUri: Uri? = null): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
    }
}

class SafFastLister(private val context: Context) {

    // In-memory directory cache: parentDocumentId -> List<FastEntry>
    private val directoryCache = ConcurrentHashMap<String, List<FastEntry>>()

    fun invalidate(parentDocumentId: String?) {
        if (!parentDocumentId.isNullOrBlank()) {
            directoryCache.remove(parentDocumentId)
        }
    }

    fun invalidateAll() {
        directoryCache.clear()
    }

    fun getTreeUriFromDoc(doc: DocumentFile): Uri? {
        return try {
            val authority = doc.uri.authority ?: return null
            val treeDocId = DocumentsContract.getTreeDocumentId(doc.uri) ?: return null
            DocumentsContract.buildTreeDocumentUri(authority, treeDocId)
        } catch (_: Exception) {
            null
        }
    }

    fun getDocumentId(treeUri: Uri, docFile: DocumentFile): String {
        return try {
            DocumentsContract.getDocumentId(docFile.uri)
        } catch (_: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(docFile.uri)
                    ?: DocumentsContract.getTreeDocumentId(treeUri)
                    ?: ""
            } catch (_: Exception) {
                DocumentsContract.getTreeDocumentId(treeUri) ?: ""
            }
        }
    }

    fun getDocumentIdFromDoc(docFile: DocumentFile): String {
        return try {
            DocumentsContract.getDocumentId(docFile.uri)
        } catch (_: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(docFile.uri) ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun listChildrenFast(
        treeUri: Uri,
        parentDocumentId: String,
        useCache: Boolean = true
    ): List<FastEntry> {
        if (parentDocumentId.isBlank()) return emptyList()

        if (useCache) {
            val cached = directoryCache[parentDocumentId]
            if (cached != null) return cached
        }

        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )

            val results = mutableListOf<FastEntry>()
            val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (c.moveToNext()) {
                    val docId = if (idIdx != -1) c.getString(idIdx) else null ?: continue
                    val displayName = if (nameIdx != -1 && !c.isNull(nameIdx)) c.getString(nameIdx) ?: "" else ""
                    val mimeType = if (mimeIdx != -1 && !c.isNull(mimeIdx)) c.getString(mimeIdx) ?: "" else ""
                    val lastMod = if (modIdx != -1 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                    val size = if (sizeIdx != -1 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    results.add(
                        FastEntry(
                            documentId = docId,
                            displayName = displayName,
                            mimeType = mimeType,
                            lastModified = lastMod,
                            size = size,
                            isDirectory = isDir,
                            uri = childUri
                        )
                    )
                }
            }

            if (useCache) {
                directoryCache[parentDocumentId] = results
            }
            results
        } catch (e: Exception) {
            Log.w("SafFastLister", "Fast listing failed for parent $parentDocumentId; falling back to DocumentFile", e)
            fallbackListChildren(treeUri, parentDocumentId, useCache)
        }
    }

    fun listChildrenFast(
        parentDir: DocumentFile,
        treeUri: Uri? = null,
        useCache: Boolean = true
    ): List<FastEntry> {
        val effectiveTreeUri = treeUri ?: getTreeUriFromDoc(parentDir)
        if (effectiveTreeUri == null) {
            return parentDir.listFiles().map { child ->
                FastEntry(
                    documentId = try { DocumentsContract.getDocumentId(child.uri) } catch (_: Exception) { child.name ?: "" },
                    displayName = child.name ?: "",
                    mimeType = child.type ?: (if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else ""),
                    lastModified = child.lastModified(),
                    size = child.length(),
                    isDirectory = child.isDirectory,
                    uri = child.uri
                )
            }
        }
        val parentDocId = getDocumentId(effectiveTreeUri, parentDir)
        return listChildrenFast(effectiveTreeUri, parentDocId, useCache)
    }

    fun findChildByName(
        treeUri: Uri,
        parentDocumentId: String,
        name: String,
        ignoreCase: Boolean = true
    ): FastEntry? {
        val entries = listChildrenFast(treeUri, parentDocumentId)
        return entries.firstOrNull { it.displayName.equals(name, ignoreCase = ignoreCase) }
    }

    private fun fallbackListChildren(
        treeUri: Uri,
        parentDocumentId: String,
        useCache: Boolean
    ): List<FastEntry> {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
            val docFile = DocumentFile.fromTreeUri(context, docUri)
                ?: DocumentFile.fromSingleUri(context, docUri)
            val children = docFile?.listFiles() ?: emptyArray()
            val list = children.map { child ->
                val childDocId = try {
                    DocumentsContract.getDocumentId(child.uri)
                } catch (_: Exception) {
                    child.name ?: ""
                }
                val mime = child.type ?: if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else ""
                FastEntry(
                    documentId = childDocId,
                    displayName = child.name ?: "",
                    mimeType = mime,
                    lastModified = child.lastModified(),
                    size = child.length(),
                    isDirectory = child.isDirectory,
                    uri = child.uri
                )
            }
            if (useCache) {
                directoryCache[parentDocumentId] = list
            }
            list
        } catch (e: Exception) {
            Log.e("SafFastLister", "Fallback listing also failed for parent $parentDocumentId", e)
            emptyList()
        }
    }
}
