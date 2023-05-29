package com.dashx.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.dashx.data.daos.NotificationDao
import com.dashx.data.entities.Notification

@Database(entities = [Notification::class], version = 1)
abstract class DashXDatabase : RoomDatabase() {
    abstract fun getNotificationDao(): NotificationDao
}
