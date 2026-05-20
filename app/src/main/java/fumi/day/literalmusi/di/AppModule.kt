package fumi.day.literalmusi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fumi.day.literalmusi.data.repository.MemoRepository
import fumi.day.literalmusi.data.repository.MemoRepositoryImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindMemoRepository(impl: MemoRepositoryImpl): MemoRepository
}
