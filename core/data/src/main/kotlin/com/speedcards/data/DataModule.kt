package com.speedcards.data

import android.content.Context
import androidx.room.Room
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.util.DayClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SpeedCardsDatabase =
        Room.databaseBuilder(context, SpeedCardsDatabase::class.java, SpeedCardsDatabase.NAME)
            .addMigrations(*SpeedCardsDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun dayClock(): DayClock = DayClock()
}
