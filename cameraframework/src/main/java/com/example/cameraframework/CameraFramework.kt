package com.example.cameraframework

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Matching iOS interface definitions
interface FrameDelegate {
    fun newImageFrameAvailable(framePointer: Any, width: Int, height: Int)
}

interface DataDelegate {
    fun newDataFrameAvailable(jointData: FloatArray)
}

// Adding PermissionDelegate to match iOS implementation
interface PermissionDelegate {
    fun permissionResultReturned(statusCode: Int)
}

class CameraFramework(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Match iOS variable naming and defaults
    private var m_cameraReady = false
    private lateinit var poseDetector: PoseDetector
    private var m_dataReady = false
    private val m_jointData = FloatArray(33 * 4) // Match iOS array size (132)
    private var m_readFrame = true
    private var m_detectingPose = true
    private var m_frameWorkReady = false

    // Match iOS member variables
    private var frameDelegate: FrameDelegate? = null
    private var dataDelegate: DataDelegate? = null
    private var permissionDelegate: PermissionDelegate? = null
    private var framePointer: ByteArray? = null
    private var frameBeingDiscarded = false
    private var m_imageRef: Image? = null

    // Camera properties
    private var cameraId: String? = null
    private var useBackCamera = true

    // Fixed resolution to match iOS hardcoded value
    private val FIXED_WIDTH = 1280
    private val FIXED_HEIGHT = 720

    // Rename delegate methods to match iOS naming conventions
    fun setFrameDelegate(delegate: FrameDelegate) {
        this.frameDelegate = delegate
        Log.d("CameraFramework", "Set video frame delegate")
    }

    fun setDelegate(delegate: DataDelegate) {
        this.dataDelegate = delegate
        Log.d("CameraFramework", "Set pose data delegate")
    }

    // Adding permission delegate method to match iOS
    fun setPermissionsDelegate(delegate: PermissionDelegate) {
        this.permissionDelegate = delegate
        Log.d("CameraFramework", "Set permissions delegate")
    }

    fun sendPermissionDataToDelegate(statusCode: Int) {
        permissionDelegate?.permissionResultReturned(statusCode)
    }

    fun discardPermissionsDelegate() {
        permissionDelegate = null
        Log.d("CameraFramework", "Discarded Permissions Delegate")
    }

    fun sendFrameDataToDelegate(frameData: ByteArray, width: Int, height: Int) {
        frameDelegate?.let {
            // Match iOS - always pass FIXED_WIDTH and FIXED_HEIGHT
            it.newImageFrameAvailable(frameData, FIXED_WIDTH, FIXED_HEIGHT)
        } ?: run {
            Log.d("CameraFramework", "FrameDelegate is null")
        }
    }

    fun sendDataToDelegate() {
        dataDelegate?.let {
            it.newDataFrameAvailable(m_jointData)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun swapInputCamera(): Boolean {
        if (cameraDevice != null) {
            // Stop current camera
            stopCamera()

            // Start with opposite camera
            useBackCamera = !useBackCamera
            return startCamera(useBackCamera, 15, 30)
        }
        return false
    }

    fun unlockFrame(): Boolean {
        if (!frameBeingDiscarded) {
            frameBeingDiscarded = true
            m_readFrame = true
            framePointer = null
            m_imageRef?.close()
            m_imageRef = null
            frameBeingDiscarded = false
        }
        return true
    }

    // Match iOS naming
    fun discardPoseDelegate() {
        Log.d("CameraFramework", "Discarded Pose Delegate")
        dataDelegate = null
    }

    fun discardFrameDelegate() {
        Log.d("CameraFramework", "Discarded Camera Delegate")
        frameDelegate = null
    }

    // Match iOS API
    fun setupVisionFrameWork(perfMode: Boolean): Boolean {
        if (!m_frameWorkReady) {
            val options = AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build()

            poseDetector = PoseDetection.getClient(options)
            m_frameWorkReady = true
            Log.d("CameraFramework", "Setup framework")
        } else {
            Log.d("CameraFramework", "Framework already setup")
        }
        return m_frameWorkReady
    }

    fun startRecognition(samplingRate: Int): Boolean {
        m_detectingPose = true
        m_readFrame = true
        return true
    }

    // Updated permission request method to be compatible with Unity
    @RequiresApi(Build.VERSION_CODES.M)
    fun requestCameraPermission() {
        val cameraPermission = android.Manifest.permission.CAMERA

        when {
            ContextCompat.checkSelfPermission(
                context,
                cameraPermission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                Log.d("CameraFramework", "Camera permission already granted")
                sendPermissionDataToDelegate(3) // 3 matches iOS "granted" status
            }
            else -> {
                // For UnityPlayerActivity, we need a different approach
                try {
                    // Try to use reflection to call requestPermissions on the activity
                    val activityClass = context.javaClass
                    val requestPermissionsMethod = activityClass.getMethod(
                        "requestPermissions",
                        Array<String>::class.java,
                        Int::class.java
                    )

                    requestPermissionsMethod.invoke(
                        context,
                        arrayOf(cameraPermission),
                        CAMERA_PERMISSION_REQUEST_CODE
                    )

                    Log.d("CameraFramework", "Requesting camera permission via reflection")
                    sendPermissionDataToDelegate(0) // 0 matches iOS "not determined" status
                } catch (e: Exception) {
                    Log.e("CameraFramework", "Failed to request permissions: ${e.message}", e)
                    // Fall back to sending a denied status
                    sendPermissionDataToDelegate(2) // 2 matches iOS "denied" status
                }
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("CameraFramework", "Exception stopping background thread: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun startCamera(
        useBack: Boolean,
        minFrames: Int,
        maxFrames: Int
    ): Boolean {
        Log.d("CameraFramework", "Initializing camera...")

        this.useBackCamera = useBack

        if (m_cameraReady) {
            Log.d("CameraFramework", "Camera already started.")
            return true
        }

        startBackgroundThread()

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            cameraId = findCameraId(cameraManager, useBack)
            if (cameraId == null) {
                Log.e("CameraFramework", "Failed to find appropriate camera")
                return false
            }

            // Set up ImageReader for camera preview
            imageReader = ImageReader.newInstance(
                FIXED_WIDTH, FIXED_HEIGHT,
                ImageFormat.YUV_420_888, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    if (m_readFrame) {
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        processVideoFrame(image)
                    } else {
                        try {
                            val image = reader.acquireLatestImage()
                            image?.close()
                        } catch (e: Exception) {
                            Log.e("CameraFramework", "Error acquiring image: ${e.message}", e)
                        }
                    }
                }, backgroundHandler)
            }

            if (!cameraOpenCloseLock.tryAcquire(5000, TimeUnit.MILLISECONDS)) {
                Log.e("CameraFramework", "Time out waiting to lock camera opening.")
                return false
            }

            cameraManager.openCamera(cameraId!!, stateCallback, backgroundHandler)
            return true
        } catch (e: CameraAccessException) {
            Log.e("CameraFramework", "Cannot access the camera: ${e.message}", e)
            return false
        } catch (e: InterruptedException) {
            Log.e("CameraFramework", "Interrupted while trying to lock camera opening: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e("CameraFramework", "Error starting camera: ${e.message}", e)
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun createCameraPreviewSession() {
        try {
            val surfaces = mutableListOf<Surface>()

            // Add ImageReader surface - this is the only surface we need
            imageReader?.surface?.let { surfaces.add(it) }

            // Set up capture request
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            // Add the ImageReader surface as target
            imageReader?.surface?.let { captureRequestBuilder?.addTarget(it) }

            // Auto-focus mode
            captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            // Auto-exposure mode
            captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            // Create session
            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        captureSession = session
                        try {
                            // Start showing preview
                            session.setRepeatingRequest(
                                captureRequestBuilder!!.build(),
                                null,
                                backgroundHandler
                            )
                            m_cameraReady = true
                            m_readFrame = true
                            Log.d("CameraFramework", "Camera started successfully")
                        } catch (e: CameraAccessException) {
                            Log.e("CameraFramework", "Failed to start camera capture: ${e.message}", e)
                            m_cameraReady = false
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraFramework", "Failed to configure camera session")
                        m_cameraReady = false
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e("CameraFramework", "Access error creating camera session: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("CameraFramework", "Error creating camera session: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun stopCamera() {
        Log.d("CameraFramework", "Stopping camera...")

        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

            m_cameraReady = false
            m_readFrame = true
        } catch (e: InterruptedException) {
            Log.e("CameraFramework", "Interrupted while stopping camera: ${e.message}", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }

        Log.d("CameraFramework", "Camera stopped successfully.")
    }

    private val stateCallback = @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            m_cameraReady = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_DEVICE -> "Fatal (device)"
                ERROR_CAMERA_DISABLED -> "Device policy"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Fatal (service)"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                else -> "Unknown"
            }
            Log.e("CameraFramework", "Camera error: $errorMsg")
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            m_cameraReady = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun findCameraId(cameraManager: CameraManager, useBack: Boolean): String? {
        val lensFacing = if (useBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

            if (facing == lensFacing) {
                return cameraId
            }
        }
        return null
    }

    private fun processVideoFrame(image: Image) {
        if (m_readFrame) {
            m_readFrame = false
            m_imageRef = image

            // Extract frame data for delegate
            frameDelegate?.let {
                // For frame delegate, we'll need to convert YUV to RGBA
                val bytes = convertImageToByteArray(image)
                framePointer = bytes
                sendFrameDataToDelegate(framePointer!!, image.width, image.height)
            }

            if (m_detectingPose && m_frameWorkReady) {
                // Convert Image to InputImage for ML Kit
                val inputImage = InputImage.fromMediaImage(
                    image,
                    getImageOrientationFromDevicePosition()
                )
//                detectPoses(inputImage)
            } else {
                Log.d(
                    "CameraFramework",
                    "m_detectingPose: $m_detectingPose, m_frameWorkReady: $m_frameWorkReady"
                )
                m_readFrame = true
                image.close()
            }
        } else {
            Log.d("CameraFramework", "Skipping frame capture, read frame is false.")
            image.close()
        }
    }

    private fun getImageOrientationFromDevicePosition(): Int {
        val isFrontFacing = !useBackCamera
        val deviceRotation = try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.rotation
        } catch (e: Exception) {
            Log.e("CameraFramework", "Failed to get device rotation: ${e.message}", e)
            Surface.ROTATION_0 // Default to 0 if there's an error
        }

        return when (deviceRotation) {
            Surface.ROTATION_0 -> if (isFrontFacing) 270 else 90
            Surface.ROTATION_90 -> if (isFrontFacing) 180 else 0
            Surface.ROTATION_180 -> if (isFrontFacing) 90 else 270
            Surface.ROTATION_270 -> if (isFrontFacing) 0 else 180
            else -> 0 // Default to 0 if an unexpected value occurs
        }
    }

    // Helper method to convert YUV to RGBA byte array
    private fun convertImageToByteArray(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()

        // Create the RGBA buffer - 4 bytes per pixel
        val rgbaBuffer = ByteArray(width * height * 4)

        // Extract the Y plane data
        val yArray = ByteArray(ySize)
        yBuffer.get(yArray)

        // Convert Y plane to RGBA (grayscale)
        for (i in 0 until width * height) {
            if (i >= ySize) break  // Ensure we don't go beyond the Y buffer

            val y = yArray[i].toInt() and 0xFF
            val pixelIndex = i * 4

            // Set RGBA values (grayscale with full alpha)
            if (pixelIndex + 3 < rgbaBuffer.size) {
                rgbaBuffer[pixelIndex] = y.toByte()     // R
                rgbaBuffer[pixelIndex + 1] = y.toByte() // G
                rgbaBuffer[pixelIndex + 2] = y.toByte() // B
                rgbaBuffer[pixelIndex + 3] = 0xFF.toByte() // Alpha
            }
        }

        return rgbaBuffer
    }

    private fun detectPoses(image: InputImage) {
        Log.d("CameraFramework", "Detecting poses in image frame...")

        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                Log.d("CameraFramework", "Pose detection successful.")
                storeLandmarkPositions(pose)
                m_imageRef?.close()
                m_readFrame = true
            }
            .addOnFailureListener { e ->
                Log.e("CameraFramework", "Pose detection failed: ${e.message}", e)
                m_imageRef?.close()
                m_readFrame = true
            }
    }

    private fun storeLandmarkPositions(pose: Pose) {
        Log.d("CameraFramework", "Storing landmark positions...")
        var index = 0
        m_dataReady = false

        for (landmark in pose.allPoseLandmarks) {
            if (index < m_jointData.size - 3) {
                m_jointData[index] = landmark.position3D.x
                m_jointData[index + 1] = landmark.position3D.y
                m_jointData[index + 2] = landmark.position3D.z
                m_jointData[index + 3] = landmark.inFrameLikelihood
                index += 4
            }
        }

        m_dataReady = true
        Log.d("CameraFramework", "Pose data stored successfully.")

        // Send data to delegate
        sendDataToDelegate()
    }


    // Match iOS DestroyAll functionality
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun destroyAll() {
        Log.d("CameraFramework", "Destroying all resources")
        stopCamera()
        m_dataReady = false
        m_frameWorkReady = false
        framePointer = null
        m_detectingPose = true
        if (::poseDetector.isInitialized) {
            poseDetector.close()
        }
        poseDetector = PoseDetection.getClient(AccuratePoseDetectorOptions.Builder().build())
        m_readFrame = true
        m_cameraReady = false
        m_imageRef = null

        // Adding discarding of delegates to match iOS behavior
        frameDelegate = null
        dataDelegate = null
        permissionDelegate = null
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001

        // ContextCompat replacement for direct permissions check
        private object ContextCompat {
            @RequiresApi(Build.VERSION_CODES.M)
            fun checkSelfPermission(context: Context, permission: String): Int {
                return context.checkSelfPermission(permission)
            }

            @RequiresApi(Build.VERSION_CODES.P)
            fun getMainExecutor(context: Context): java.util.concurrent.Executor {
                return context.mainExecutor
            }
        }

        // Singleton pattern to match iOS implementation
        @Volatile
        private var INSTANCE: CameraFramework? = null

        fun sharedManager(context: Context): CameraFramework {
            return INSTANCE ?: synchronized(this) {
                val instance = CameraFramework(context)
                INSTANCE = instance
                instance
            }
        }
    }
}