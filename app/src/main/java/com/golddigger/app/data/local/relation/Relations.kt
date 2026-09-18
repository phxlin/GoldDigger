package com.golddigger.app.data.local.relation

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef

/**
 * One holding flattened together with its company info and latest cached quote.
 * Populated by a LEFT JOIN so [price] is null until the first successful sync.
 */
data class HoldingRow(
    val id: Long,
    val ticker: String,
    val shares: Double,
    val costBasis: Double,
    val dateAdded: Long,
    val companyName: String,
    val sector: String?,
    val isEtf: Boolean = false,
    val price: Double?,
    val dayChangePct: Double?,
    val priceUpdatedAt: Long?,
)

data class GroupWithStocks(
    @Embedded val group: GroupEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "ticker",
        associateBy = Junction(
            value = StockGroupCrossRef::class,
            parentColumn = "groupId",
            entityColumn = "ticker",
        ),
    )
    val stocks: List<StockEntity>,
)
