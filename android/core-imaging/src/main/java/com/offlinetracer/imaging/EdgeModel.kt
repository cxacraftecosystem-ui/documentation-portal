package com.offlinetracer.imaging

interface EdgeModel {
    val id: String

    val displayName: String

    val isAvailable: Boolean

    fun infer(src: GrayF): GrayF
}

object NoEdgeModel : EdgeModel {
    override val id: String get() = ""
    override val displayName: String get() = "None (classical engines)"
    override val isAvailable: Boolean get() = false

    override fun infer(src: GrayF): GrayF =
        throw UnsupportedOperationException(
            "No edge model is installed. Check EdgeModel.isAvailable before calling infer()."
        )
}

object EdgeModelRegistry {

    private val models = LinkedHashMap<String, EdgeModel>()

    @Synchronized
    fun register(model: EdgeModel) {
        if (model.id.isEmpty()) return
        models[model.id] = model
    }

    @Synchronized
    fun available(): List<EdgeModel> {
        val out = ArrayList<EdgeModel>(models.size)
        for (m in models.values) if (m.isAvailable) out.add(m)
        return out
    }

    @Synchronized
    fun all(): List<EdgeModel> = ArrayList(models.values)

    @Synchronized
    fun byId(id: String): EdgeModel? = models[id]

    @Synchronized
    fun clear() {
        models.clear()
    }
}
