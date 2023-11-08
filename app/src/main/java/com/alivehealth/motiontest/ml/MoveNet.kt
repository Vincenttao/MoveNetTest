package com.alivehealth.motiontest.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.*
import android.graphics.RectF
import android.os.SystemClock
import com.alivehealth.motiontest.data.BodyPart
import com.alivehealth.motiontest.data.Device
import com.alivehealth.motiontest.data.Person
import com.alivehealth.motiontest.data.KeyPoint
import com.alivehealth.motiontest.data.TorsoAndBodyDistance
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ModelType {
    Lightning,
    Thunder
}

class MoveNet(private val interpreter: Interpreter, private var gpuDelegate: GpuDelegate?) :
    PoseDetector {
        companion object{
            private const val MIN_CROP_KEYPOINT_SCORE = .2f
            private const val CPU_NUM_THREADS = 4
            // Parameters that control how large crop region should be expanded from previous frames'
            // body keypoints.
            private const val TORSO_EXPANSION_RATIO = 1.9f
            private const val BODY_EXPANSION_RATIO = 1.2f
            // TFLite file names.
            private const val LIGHTNING_FILENAME = "movenet_lightning.tflite"
            private const val THUNDER_FILENAME = "movenet_thunder.tflite"

            //在 Android 开发中，Context 类是一个抽象类，它允许访问特定资源和类以及调用应用级别的操作，如启动活动、广播和接收意图等。
            fun create(context: Context, device: Device, modelType: ModelType): MoveNet{
                val options = Interpreter.Options()//Interpreter.Options 是 Interpreter 类的一个嵌套类，用于配置 Interpreter 的运行时选项。
                var gpuDelegate: GpuDelegate? = null
                options.setNumThreads(CPU_NUM_THREADS)
                when(device) {
                    Device.CPU -> {

                    }
                    Device.GPU -> {
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                    }
                    Device.NNAPI -> options.setUseNNAPI(true)
                }
                return MoveNet(
                    Interpreter(
                        FileUtil.loadMappedFile( // FileUtil.loadMappedFile 方法来加载模型文件。返回MappedByteBuffer 对象
                            context,
                            if (modelType == ModelType.Lightning) LIGHTNING_FILENAME
                            else THUNDER_FILENAME
                        ), options
                    ),
                    gpuDelegate
                )

            }

            // default to lightning.
            fun create(context: Context,device: Device): MoveNet =
                create(context, device, ModelType.Lightning)
        }

    private var cropRegion: RectF? = null
    private var lastInferenceTimeNanos: Long = -1
    /*获取输入张量的宽和高，获取输出张量的形状
     */
    private val inputWidth = interpreter.getInputTensor(0).shape()[1]
    private val inputHeight = interpreter.getInputTensor(0).shape()[2]
    private var outputShape: IntArray = interpreter.getOutputTensor(0).shape()

    override fun estimatePoses(bitmap: Bitmap): List<Person> {
        val inferenceStartTimeNanos = SystemClock.elapsedRealtimeNanos()
        if (cropRegion == null) {
            cropRegion = initRectF (bitmap.width, bitmap.height)//输入一个位图，并截取为正方形输出坐标
        }
        var totalScore = 0f

        val numKeyPoints = outputShape[2] //指模型识别出的身体关键点的数量。
        val keyPoints = mutableListOf<KeyPoint>()//keyPoints 将是一个空的 KeyPoint 对象的可变列表，可以向其中添加或删除 KeyPoint 实例。

        //这是使用 Kotlin 的 run 函数，当 cropRegion 不为 null 时执行大括号内的代码块。
        // run 函数内部的代码可以直接访问 cropRegion 的成员，无需额外的空值检查。生成待处理的detectBitmap
        cropRegion?.run {
            //转化为绝对坐标
            val rect = RectF(
                (left * bitmap.width),
                (top * bitmap.height),
                (right * bitmap.width),
                (bottom * bitmap.height)
            )
            //生成裁剪位图对象
            val detectBitmap = Bitmap.createBitmap(
                rect.width().toInt(),
                rect.height().toInt(),
                Bitmap.Config.ARGB_8888
            )
            //通过 Canvas(detectBitmap).drawBitmap 方法将 bitmap 的对应裁剪区域绘制到新创建的 detectBitmap 上
            Canvas(detectBitmap).drawBitmap(
                bitmap,
                -rect.left,
                -rect.top,
                null
            )

            val inputTensor = processInputImage(detectBitmap, inputWidth, inputHeight)
            //创建一个TensorBuffer对象
            val outputTensor = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)
            val widthRatio = detectBitmap.width.toFloat() / inputWidth
            val heightRatio = detectBitmap.height.toFloat() / inputHeight

            val positions = mutableListOf<Float>()

            inputTensor?.let { input ->  //inputTensor的值会传递给input
                //调用推理，进行处理，推理结果存入outputTensor
                interpreter.run(input.buffer, outputTensor.buffer.rewind())

                val output = outputTensor.floatArray

                for (idx in 0 until numKeyPoints) {
                    val x = output[idx * 3 + 1] * inputWidth * widthRatio
                    val y = output[idx * 3 + 0] * inputHeight * heightRatio

                    positions.add(x)
                    positions.add(y)
                    val score = output[idx * 3 + 2]
                    keyPoints.add(
                        KeyPoint(
                            BodyPart.fromInt(idx),
                            PointF(
                                x,
                                y
                            ),
                            score
                        )
                    )
                    totalScore += score
                }
            }

            val matrix = Matrix()
            val points = positions.toFloatArray()

            matrix.postTranslate(rect.left, rect.top)
            matrix.mapPoints(points)
            keyPoints.forEachIndexed { index, keyPoint ->
                keyPoint.coordinate =
                    PointF(
                        points[index * 2],
                        points[index * 2 + 1]
                    )
            }
            // 生成新的一个图片处理区域
            cropRegion = determineRectF(keyPoints, bitmap.width, bitmap.height)
        }

        lastInferenceTimeNanos =
            SystemClock.elapsedRealtimeNanos() - inferenceStartTimeNanos

        return listOf(Person(keyPoints = keyPoints, score = totalScore / numKeyPoints))
    }

    override fun lastInferenceTimeNanos(): Long = lastInferenceTimeNanos

    override fun close() {
        gpuDelegate?.close()
        interpreter.close()
        cropRegion = null
    }


    /**
     * 检查在姿态估计中躯干部分的关键点是否足够可见。检查模型是否对至少一个肩部或髋部的关键点的预测足够有信心
     * 有信心则返回true
     */
    private fun torsoVisible(keyPoints: List<KeyPoint>): Boolean {
        return ((keyPoints[BodyPart.LEFT_HIP.position].score > MIN_CROP_KEYPOINT_SCORE).or(
            keyPoints[BodyPart.RIGHT_HIP.position].score > MIN_CROP_KEYPOINT_SCORE
        )).and(
            (keyPoints[BodyPart.LEFT_SHOULDER.position].score > MIN_CROP_KEYPOINT_SCORE).or(
                keyPoints[BodyPart.RIGHT_SHOULDER.position].score > MIN_CROP_KEYPOINT_SCORE
            )
        )
    }

    /**
     * 这段代码是 MoveNet 姿态估计模型的一部分，它的作用是确定用于模型推断的图像裁剪区域。
     * 这个区域是基于上一帧中检测到的关节来估计的，目的是包围目标人物的全身，并以两个髋关节的中点为中心。
     * When the model is not confident with the four torso joint predictions, the
     * function returns a default crop which is the full image padded to square.
     */
    private fun determineRectF(
        keyPoints: List<KeyPoint>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        val targetKeyPoints = mutableListOf<KeyPoint>()
        keyPoints.forEach {
            targetKeyPoints.add(
                KeyPoint(
                    it.bodyPart,
                    PointF(
                        it.coordinate.x,
                        it.coordinate.y
                    ),
                    it.score
                )
            )
        }
        if (torsoVisible(keyPoints)) {
            val centerX =
                (targetKeyPoints[BodyPart.LEFT_HIP.position].coordinate.x +
                        targetKeyPoints[BodyPart.RIGHT_HIP.position].coordinate.x) / 2f
            val centerY =
                (targetKeyPoints[BodyPart.LEFT_HIP.position].coordinate.y +
                        targetKeyPoints[BodyPart.RIGHT_HIP.position].coordinate.y) / 2f

            val torsoAndBodyDistances =
                determineTorsoAndBodyDistances(keyPoints, targetKeyPoints, centerX, centerY)

            val list = listOf(
                torsoAndBodyDistances.maxTorsoXDistance * TORSO_EXPANSION_RATIO,
                torsoAndBodyDistances.maxTorsoYDistance * TORSO_EXPANSION_RATIO,
                torsoAndBodyDistances.maxBodyXDistance * BODY_EXPANSION_RATIO,
                torsoAndBodyDistances.maxBodyYDistance * BODY_EXPANSION_RATIO
            )

            var cropLengthHalf = list.maxOrNull() ?: 0f
            val tmp = listOf(centerX, imageWidth - centerX, centerY, imageHeight - centerY)
            cropLengthHalf = min(cropLengthHalf, tmp.maxOrNull() ?: 0f)
            val cropCorner = Pair(centerY - cropLengthHalf, centerX - cropLengthHalf)

            return if (cropLengthHalf > max(imageWidth, imageHeight) / 2f) {
                initRectF(imageWidth, imageHeight)
            } else {
                val cropLength = cropLengthHalf * 2
                RectF(
                    cropCorner.second / imageWidth,
                    cropCorner.first / imageHeight,
                    (cropCorner.second + cropLength) / imageWidth,
                    (cropCorner.first + cropLength) / imageHeight,
                )
            }
        } else {
            return initRectF(imageWidth, imageHeight)
        }
    }

    /**
     *将其转换为机器学习模型所需的输入格式。
     */
    private fun processInputImage(bitmap: Bitmap, inputWidth: Int, inputHeight: Int): TensorImage? {
        val width: Int = bitmap.width
        val height: Int = bitmap.height

        val size = if (height > width) width else height
        val imageProcessor = ImageProcessor.Builder().apply {
            add(ResizeWithCropOrPadOp(size, size))
            add(ResizeOp(inputWidth, inputHeight, ResizeOp.ResizeMethod.BILINEAR))
        }.build()
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        return imageProcessor.process(tensorImage)
    }

    /**
     * initRectF 函数确保无论原始图片是横向的还是纵向的，裁剪区域始终是正方形的，并且在原始图片内居中
     * 返回四个角的百分比
     */
    private fun initRectF(imageWidth: Int, imageHeight: Int): RectF {
        val xMin: Float
        val yMin: Float
        val width: Float
        val height: Float
        if (imageWidth > imageHeight) {
            width = 1f
            height = imageWidth.toFloat() / imageHeight
            xMin = 0f
            yMin = (imageHeight / 2f - imageWidth / 2f) / imageHeight
        } else {
            height = 1f
            width = imageHeight.toFloat() / imageWidth
            yMin = 0f
            xMin = (imageWidth / 2f - imageHeight / 2) / imageWidth
        }
        return RectF(
            xMin,
            yMin,
            xMin + width,
            yMin + height
        )
    }

    /**
     * Calculates the maximum distance from each keypoints to the center location.
     * The function returns the maximum distances from the two sets of keypoints:
     * full 17 keypoints and 4 torso keypoints. The returned information will be
     * used to determine the crop size. See determineRectF for more detail.
     */
    private fun determineTorsoAndBodyDistances(
        keyPoints: List<KeyPoint>,
        targetKeyPoints: List<KeyPoint>,
        centerX: Float,
        centerY: Float
    ): TorsoAndBodyDistance {
        val torsoJoints = listOf(
            BodyPart.LEFT_SHOULDER.position,
            BodyPart.RIGHT_SHOULDER.position,
            BodyPart.LEFT_HIP.position,
            BodyPart.RIGHT_HIP.position
        )

        var maxTorsoYRange = 0f
        var maxTorsoXRange = 0f
        torsoJoints.forEach { joint ->
            val distY = abs(centerY - targetKeyPoints[joint].coordinate.y)
            val distX = abs(centerX - targetKeyPoints[joint].coordinate.x)
            if (distY > maxTorsoYRange) maxTorsoYRange = distY
            if (distX > maxTorsoXRange) maxTorsoXRange = distX
        }

        var maxBodyYRange = 0f
        var maxBodyXRange = 0f
        for (joint in keyPoints.indices) {
            if (keyPoints[joint].score < MIN_CROP_KEYPOINT_SCORE) continue
            val distY = abs(centerY - keyPoints[joint].coordinate.y)
            val distX = abs(centerX - keyPoints[joint].coordinate.x)

            if (distY > maxBodyYRange) maxBodyYRange = distY
            if (distX > maxBodyXRange) maxBodyXRange = distX
        }
        return TorsoAndBodyDistance(
            maxTorsoYRange,
            maxTorsoXRange,
            maxBodyYRange,
            maxBodyXRange
        )
    }

    }