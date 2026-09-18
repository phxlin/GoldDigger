package com.golddigger.app.data.local

import androidx.room.TypeConverter
import com.golddigger.app.data.local.entity.TransactionType

class Converters {
    @TypeConverter
    fun toTransactionType(value: String?): TransactionType? =
        value?.let { TransactionType.valueOf(it) }

    @TypeConverter
    fun fromTransactionType(type: TransactionType?): String? = type?.name
}
