package com.offlinetracer.imaging

/**
 * Optional learned edge detector (ALGORITHMS §7.6).
 *
 * **No weights ship with the app and none are downloaded.** Everything in the pipeline works without
 * a model; this interface exists so that a user who side-loads a PiDiNet / HED / DexiNed file can
 * substitute it for the classical engines while every other stage stays exactly as it was. The
 * platform layer owns the runtime (ONNX, NNAPI, whatever) — `:core-imaging` stays pure Kotlin and
 * only ever sees [GrayF] in and [GrayF] out.
 *
 * ### What a side-loader actually has to provide
 *
 * This is the whole contract, stated here because it is the only thing standing between "the interface
 * exists" and somebody being able to use it. There is no shipped implementation and no downloader —
 * the Android manifest holds no `INTERNET` permission, so there is nowhere for one to download from.
 *
 * An implementer supplies **two** things:
 *
 *  1. **A runtime.** An inference engine on the host's classpath — ONNX Runtime Mobile, TFLite, NNAPI
 *     via a thin JNI shim, anything — plus the weights file, both provided by the user. Neither is a
 *     dependency of this module and neither will become one: `:core-imaging` is plain Kotlin/JVM with
 *     no third-party dependency at all, which is what lets the identical code run under a JVM test, on
 *     Android, and (ported) in a browser.
 *  2. **A class implementing this interface**, registered with [EdgeModelRegistry.register] before a
 *     trace starts, satisfying every clause below. The pipeline checks the ones it can:
 *
 *      - [id] is stable across app versions — it is persisted in `TraceParams.modelId` inside project
 *        files, so changing it orphans every project that used the model.
 *      - [isAvailable] is `false` until [infer] can genuinely run. Weights still loading, wrong ABI,
 *        runtime failed to initialise: all `false`. The pipeline routes an unavailable model to the
 *        classical engine and says which one ran instead.
 *      - [infer] returns a map of **exactly** the input's dimensions. A different size is discarded
 *        with a note; it is not resampled, because a model that returns the wrong shape has
 *        misunderstood its input and scaling its output would hide that.
 *      - [infer] returns edge **probability**, 1 = edge. This is the opposite polarity to every DoG
 *        engine in this module, and getting it backwards produces a photographic negative of a
 *        drawing rather than an error.
 *      - [infer] may throw. The pipeline catches it, names the model and the throwable in a note, and
 *        falls back — a third-party native library on an unknown ABI must not be able to take the
 *        trace down with it.
 *      - [infer] must be safe to call from a worker thread and must not mutate its argument.
 *
 * What it must *not* do is return zeros when it cannot run. See [NoEdgeModel].
 */
interface EdgeModel {
    /** Stable identifier persisted in a project file; must not change between app versions. */
    val id: String

    /** Human-readable name for the model picker. */
    val displayName: String

    /**
     * Whether [infer] can actually run right now — weights present, runtime initialised, ABI
     * supported. A registered-but-unavailable model is normal and the UI is expected to say so
     * rather than hide it, because "my model disappeared" is otherwise unanswerable.
     */
    val isAvailable: Boolean

    /**
     * Runs the model.
     *
     * @param src working-resolution greyscale, conventionally 0..1.
     * @return an edge **probability** map in 0..1 the same size as [src], where 1 = edge. Note this
     *   is the opposite polarity to [EdgeDog.xdog], which returns ink density with 1 = paper.
     */
    fun infer(src: GrayF): GrayF
}

/**
 * The default: no model at all.
 *
 * [isAvailable] is `false` and [infer] throws, so a caller that reaches inference without checking
 * fails loudly at the one place the mistake is obvious, rather than returning zeros that look like
 * "the model found no edges in your artwork".
 */
object NoEdgeModel : EdgeModel {
    override val id: String get() = ""
    override val displayName: String get() = "None (classical engines)"
    override val isAvailable: Boolean get() = false

    override fun infer(src: GrayF): GrayF =
        throw UnsupportedOperationException(
            "No edge model is installed. Check EdgeModel.isAvailable before calling infer()."
        )
}

/**
 * Registry of side-loaded models, keyed by [EdgeModel.id] and ordered by registration.
 *
 * Registration order is preserved so the model picker does not reshuffle itself between launches,
 * and every accessor is synchronised because models are registered from the platform layer while the
 * pipeline may already be running on a worker thread.
 */
object EdgeModelRegistry {

    private val models = LinkedHashMap<String, EdgeModel>()

    /**
     * Adds [model], replacing any previously registered model with the same id **in place** so the
     * display order does not change when a model is re-registered after its weights finish loading.
     *
     * A model with a blank id is ignored rather than rejected: it can only come from a malformed
     * side-loaded bundle, and taking down the app over one is a worse outcome than not listing it.
     */
    @Synchronized
    fun register(model: EdgeModel) {
        if (model.id.isEmpty()) return
        models[model.id] = model
    }

    /**
     * @return every registered model whose [EdgeModel.isAvailable] is true, in registration order.
     *   Empty is the normal case — it means the app is running on classical engines.
     */
    @Synchronized
    fun available(): List<EdgeModel> {
        val out = ArrayList<EdgeModel>(models.size)
        for (m in models.values) if (m.isAvailable) out.add(m)
        return out
    }

    /**
     * @return every registered model, **available or not**, in registration order.
     *
     * This is what a model picker lists, and it is a separate accessor from [available] on purpose. A
     * picker built on [available] hides a model whose weights failed to load, which turns "my model
     * stopped working" into a question with no answer anywhere on screen: the entry is simply gone, and
     * nothing distinguishes that from never having registered it. A picker built on this one shows the
     * entry greyed out, which is a fact the user can act on. Whether a model may be *selected* is
     * [EdgeModel.isAvailable]; whether it exists is this.
     */
    @Synchronized
    fun all(): List<EdgeModel> = ArrayList(models.values)

    /**
     * @return the registered model with this id regardless of availability, or `null` if there is
     *   none. Callers resolve `null` to [NoEdgeModel] and are expected to add a note saying the
     *   requested model was missing — silently falling back is the failure this project cares about.
     */
    @Synchronized
    fun byId(id: String): EdgeModel? = models[id]

    /** Drops every registration. Exists for tests and for a "forget side-loaded models" action. */
    @Synchronized
    fun clear() {
        models.clear()
    }
}
