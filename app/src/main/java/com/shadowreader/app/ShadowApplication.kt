package com.shadowreader.app

import android.app.Application
import androidx.room.Room
import com.shadowreader.app.data.ArticleDatabase

class ShadowApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, ArticleDatabase::class.java, "shadow-reader.db")
            .addMigrations(ArticleDatabase.MIGRATION_1_2, ArticleDatabase.MIGRATION_2_3).build()
    }
}
