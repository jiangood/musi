package fumi.day.literalmusi.data.repository

import fumi.day.literalmusi.domain.model.Memo
import kotlinx.coroutines.flow.Flow

interface MemoRepository {
    fun observeAll(): Flow<List<Memo>>
    suspend fun getByFileName(fileName: String): Memo?
    suspend fun save(memo: Memo)
    suspend fun delete(fileName: String)
}
