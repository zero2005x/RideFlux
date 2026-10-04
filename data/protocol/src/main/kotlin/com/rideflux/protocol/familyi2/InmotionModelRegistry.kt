package com.rideflux.protocol.familyi2

/** Vendor-static model identities. A row is not proof that its wire offsets are decoded. */
object InmotionModelRegistry {
    enum class Generation { EZCAN, LORIN }
    data class Model(
        val key: String,
        val generation: Generation,
        val effectiveFieldCount: Int?,
        val minPayloadLength: Int?,
    )

    private val models = listOf(
        Model("v5", Generation.EZCAN, 9, null),
        Model("v5f", Generation.EZCAN, 9, null),
        Model("v8", Generation.EZCAN, 10, null),
        Model("v8f", Generation.EZCAN, 10, null),
        Model("v8s", Generation.EZCAN, 10, null),
        Model("v10", Generation.EZCAN, null, null),
        Model("v10f", Generation.EZCAN, null, null),
        Model("c10", Generation.LORIN, 7, 30),
        Model("e10", Generation.LORIN, 32, 74),
        Model("e15", Generation.LORIN, 32, 74),
        Model("e20", Generation.LORIN, 32, 54),
        Model("lx203", Generation.LORIN, 9, 18),
        Model("lx207", Generation.LORIN, 9, 18),
        Model("lx208", Generation.LORIN, 9, 19),
        Model("lx208pro", Generation.LORIN, 7, 34),
        Model("p6", Generation.LORIN, 32, 74),
        Model("rs", Generation.LORIN, 7, 30),
        Model("rsgtx", Generation.LORIN, 7, 40),
        Model("rsjet", Generation.LORIN, 7, 30),
        Model("s1", Generation.LORIN, 9, 18),
        Model("v11", Generation.LORIN, 21, 56),
        Model("v11y", Generation.LORIN, 32, 74),
        Model("v12", Generation.LORIN, 23, 54),
        Model("v12pro", Generation.LORIN, 23, 54),
        Model("v12s", Generation.LORIN, 32, 74),
        Model("v13", Generation.LORIN, 32, 74),
        Model("v13pro", Generation.LORIN, 32, 74),
        Model("v14", Generation.LORIN, 32, 74),
        Model("v6", Generation.LORIN, 32, 54),
        Model("v9", Generation.LORIN, 32, 74),
        Model("x1", Generation.LORIN, 31, 74),
    ).associateBy { it.key }

    val all: Collection<Model> get() = models.values

    /** Exact normalized key match; v11 must never match v11y. */
    fun find(modelName: String): Model? {
        val key = modelName.trim().lowercase().replace(Regex("[-_]1$"), "")
            .filter(Char::isLetterOrDigit)
        return models[key]
    }
}
