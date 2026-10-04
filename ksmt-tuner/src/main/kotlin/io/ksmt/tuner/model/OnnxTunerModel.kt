package io.ksmt.tuner.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import io.ksmt.tuner.feature.FeatureSchema
import io.ksmt.tuner.feature.FormulaFeatures
import io.ksmt.tuner.params.SolverTuningParams
import java.nio.FloatBuffer
import java.nio.file.Path

/**
 * [TunerModel] backed by an ONNX Runtime session.
 *
 * A working scaffold: it loads an `.onnx` model, feeds the schema-aligned
 * feature vector as a `{1, N}` float tensor and runs inference.
 *
 * The exact output tensor layout is not defined until a real model is trained;
 * [decodeResult] is the single place to change once that format is known.
 */
class OnnxTunerModel(
    private val modelPath: Path,
    private val schema: FeatureSchema
) : TunerModel, AutoCloseable {

    override val name: String = "onnx"

    private val environment: OrtEnvironment
    private val session: OrtSession
    private val inputName: String
    private val inputShape: LongArray

    init {
        // Catch Throwable instead of a narrower type because ONNX Runtime also
        // surfaces native loader failures (e.g. UnsatisfiedLinkError) as thrown.
        @Suppress("TooGenericExceptionCaught")
        try {
            environment = OrtEnvironment.getEnvironment()
            session = environment.createSession(modelPath.toString(), OrtSession.SessionOptions())
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to load ONNX model from '$modelPath'", e)
        }

        val declaredInput = session.inputNames.firstOrNull()
            ?: throw IllegalStateException("ONNX model '$modelPath' has no declared input")
        inputName = declaredInput
        inputShape = longArrayOf(1L, schema.dimension.toLong())
    }

    override fun predict(features: FormulaFeatures): SolverTuningParams {
        features.validateDimensions()

        val result = try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(features.toFloatArray()), inputShape).use { tensor ->
                session.run(mapOf(inputName to tensor))
            }
        } catch (e: OrtException) {
            throw IllegalStateException("ONNX inference failed for model '$modelPath'", e)
        }

        return result.use { decodeResult(it) }
    }

    /**
     * Decodes the inference outputs into [SolverTuningParams].
     *
     * The mapping from the model output tensor to typed parameters is not yet
     * defined (no model is trained in this module's scope). The order/structure
     * of the output tensor must be aligned with the learned parameter catalog.
     *
     * TODO(model): define the output schema and implement decoding. Do not guess
     * dimensions here; fail loudly instead.
     */
    private fun decodeResult(result: OrtSession.Result): SolverTuningParams {
        require(result.size() == 1) {
            "Expected a single ONNX output tensor, but got ${result.size()}"
        }
        val output = result.iterator().next().value
        require(output is OnnxTensor) {
            "ONNX model output must be a tensor, but was ${output.javaClass.simpleName}"
        }
        throw NotImplementedError(
            "ONNX output decoding is not implemented yet. " +
                "A model is not trained, so the output tensor layout is undefined " +
                "(model='$modelPath', input dimension=${schema.dimension})."
        )
    }

    override fun close() {
        session.close()
        // The OrtEnvironment is a process-wide singleton; it is intentionally
        // not closed here so that other clients of the same environment are unaffected.
    }
}
