package fumi.day.literalmusi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.data.repository.MusicRepositoryImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository
}
