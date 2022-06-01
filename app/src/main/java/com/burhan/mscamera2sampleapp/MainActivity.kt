package com.burhan.mscamera2sampleapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.burhan.mscamera2sampleapp.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Long
import java.util.*
import java.util.Arrays.asList


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var textureView : AutoFitTextureView? = null
    private var takePhotoBtn : ImageButton? = null
    private var rotateCamera : ImageButton? = null
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK

    private var cameraDevice : CameraDevice? = null
    private var cameraId : String? = null

    private var backgroundHandler : Handler? = null
    private var backgroundHandlerThread : HandlerThread? = null

    private var ORIENTATIONS : SparseIntArray? = null
    private lateinit var previewSize : Size

    private var captureRequestBuilder : CaptureRequest.Builder? = null
    private var previewCaptureSession : CameraCaptureSession? = null

    private var imageReader : ImageReader? = null

    private val MAX_PREVIEW_WIDTH = 1920
    private val MAX_PREVIEW_HEIGHT = 1080

    private val STATE_PEEVIEW = 0
    private val STATE_WAIT_LOCK = 1
    private var captureState = STATE_PEEVIEW

    private lateinit var file : File

    private var currentExtension = -1
    private var currentExtensionIdx = -1

    private lateinit var cameraExtensionSession: CameraExtensionSession
    private lateinit var extensionCharacteristics: CameraExtensionCharacteristics

    private var orientations : SparseIntArray = SparseIntArray(4).apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        textureView  =  binding.viewFinder
        takePhotoBtn = binding.takePhotoBtn
        rotateCamera = binding.rotateCamera

        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                window.navigationBarColor = getColor(R.color.white)

                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    // Do not let system steal touches for showing the navigation bar
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                            // Hide the nav bar and status bar
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            // Keep the app content behind the bars even if user swipes them up
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

//            @Suppress("DEPRECATION")
//            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }

        file = File(getExternalFilesDir(null), FILE_NAME_FORMAT)

        setContentView(binding.root)

        startOtherFunctionality()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startOtherFunctionality(){
        takePhotoBtn!!.setOnClickListener(){
            lockFocus()
        }

        rotateCamera!!.setOnClickListener(){
            rotateLens()
        }

//        textureView!!.setOnTouchListener { v, event ->
//
//            val pointerId = event.getPointerId(0)
//            val pointerIndex = event.findPointerIndex(pointerId)
//            // Get the pointer's current position
//            // Get the pointer's current position
//            val x = event.getX(pointerIndex)
//            val y = event.getY(pointerIndex)
//
//            val touchRect = Rect(
//                    (x - 100).toInt(),
//                    (y - 100).toInt(),
//                    (x + 100).toInt(),
//                    (y + 100).toInt())
//
//            if(cameraId != null){
//                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
//                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId!!)
//                val focusArea = MeteringRectangle(touchRect, MeteringRectangle.METERING_WEIGHT_DONT_CARE)
//
//                captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
//                try {
//                    previewCaptureSession?.capture(captureRequestBuilder?.build()!!, previewCaptureCallbacks,
//                            backgroundHandler)
//                    // After this, the camera will go back to the normal state of preview.
//                    captureState = STATE_PEEVIEW
//                } catch (e: CameraAccessException) {
//                    e.printStackTrace()
//                }
//
//                captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusArea))
//                captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
//                captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE,
//                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
//                captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                        CameraMetadata.CONTROL_AF_TRIGGER_START)
//                captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
//                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
//                try {
//                    previewCaptureSession?.setRepeatingRequest(captureRequestBuilder?.build()!!, previewCaptureCallbacks,
//                            backgroundHandler)
//                } catch (e: CameraAccessException) {
//                    e.printStackTrace()
//                }
//
//            }
//            v?.onTouchEvent(event) ?: true
//        }
    }

    /**
    This callback gives us a notification when we are ready to prepare for the camera device initialization.
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            setupCamera(width, height)
            transformImage(width, height)
            connectCamera()
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            setupCamera(width, height)
            transformImage(width, height)
            connectCamera()
        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return false
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }


    /**
    This is used to check a camera device state. It is required to open a camera.
     */
    private val cameraStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Toast.makeText(this@MainActivity, "Camera Connection Established!!", Toast.LENGTH_SHORT).show()
            startPreview()
        }

        override fun onError(camera: CameraDevice, p1: Int) {
            camera.close()
//            Toast.makeText(this@MainActivity, "Camera Connection Error!!", Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
//            Toast.makeText(this@MainActivity, "Camera Connection Disconnected!!", Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onClosed(camera: CameraDevice) {
            super.onClosed(camera)
            Toast.makeText(this@MainActivity, "Camera Connection Closed!!", Toast.LENGTH_SHORT).show()

        }
    }


    /**
    A callback for configuring capture sessions from a camera.
    This is needed to check if the camera session is configured and ready to show a preview.
     */
    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {

        override fun onConfigured(captureSession: CameraCaptureSession) {
            previewCaptureSession = captureSession

            try {

                previewCaptureSession?.setRepeatingRequest(
                        captureRequestBuilder?.build()!!,
                        null,
                        backgroundHandler
                )

            }catch (e:CameraAccessException){
                e.printStackTrace()
            }

        }
        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
            Toast.makeText(this@MainActivity, "Unable to setup camera preview", Toast.LENGTH_SHORT).show()
        }
    }


    /**
    This callback manages captured sessions.
    Whenever a focus or a still picture is requested from a user,
    CameraCaptureSession returns callbacks through this callback.
     */
    private val previewCaptureCallbacks = object : CameraCaptureSession.CaptureCallback(){

        private  fun process(captureResult: CaptureResult){
            when(captureState){
                STATE_PEEVIEW -> {
                }
                STATE_WAIT_LOCK -> {
                    captureState = STATE_PEEVIEW
                    val afState = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || lensFacing == CaptureRequest.LENS_FACING_FRONT) {
                        startStillCaptureRequest()
                    }
                }
            }
        }

        override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }
    }


    /**
    This callback returns an image when CameraCaptureSession completes capture.
    We need to set this against ImageReader before capturing a still picture.
     */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
            backgroundHandler?.post(ImageSaver(reader.acquireNextImage(), file))
        }


    /**
    This method setup the camera by selecting the cameraDevice as per the useCase.
     */
    private fun setupCamera(width: Int, height: Int){
        val cameraManager : CameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        for(cameraid : String in cameraManager.cameraIdList){
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraid)
            if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) != lensFacing){
                continue
            }

            val map = cameraCharacteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val previewSizeList = map.getOutputSizes(ImageFormat.JPEG)
            var largest : Size = previewSizeList[0]

            for(size in previewSizeList){
                if(size.width * size.height > largest.width * largest.height){
                    largest = size
                }
            }

            imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 1)
            imageReader!!.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)

            val displayRotation = windowManager.defaultDisplay.rotation
            val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            var swappedDimensions = false

            when (displayRotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> if (sensorOrientation === 90 || sensorOrientation === 270) {
                    swappedDimensions = true
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> if (sensorOrientation === 0 || sensorOrientation === 180) {
                    swappedDimensions = true
                }
                else -> Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }

            val displaySize = Point()
            windowManager.defaultDisplay.getSize(displaySize)

            var rotatedPreviewWidth = width
            var rotatedPreviewHeight = height
            var maxPreviewWidth = displaySize.x
            var maxPreviewHeight = displaySize.y

            if (swappedDimensions) {
                rotatedPreviewWidth = height;
                rotatedPreviewHeight = width;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }

            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }

            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }

            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                    maxPreviewHeight, largest)!!

            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView?.setAspectRatio(
                        previewSize.getWidth(), previewSize.getHeight())
            } else {
                textureView?.setAspectRatio(
                        previewSize.getHeight(), previewSize.getWidth())
            }


            cameraId = cameraid

            return
        }
    }

    
    /**
    This method check the camera permission and if granted open the camera.
     */
    private fun connectCamera(){
        val cameraManager : CameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSION,
                    REQUEST_CODE_PERMISSIONS
            )
            return
        }

        cameraManager.openCamera(cameraId!!, cameraStateCallback, backgroundHandler)
    }


    /**
    This method start the preview after the camera device is opened.
     */
    private fun startPreview(){
        val surfaceTexture = textureView?.surfaceTexture
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(surfaceTexture)

        captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder?.addTarget(previewSurface)

        cameraDevice?.createCaptureSession(
                asList(previewSurface, imageReader?.surface),
                captureStateCallback,
                backgroundHandler
        )

//        val outputConfig = asList(
//                OutputConfiguration1(imageReader?.surface!!),
//                OutputConfiguration1(previewSurface)
//        )
//        val extensionConfiguration = ExtensionSessionConfiguration(
//                CameraExtensionCharacteristics.EXTENSION_BOKEH,
//                outputConfig,
//                mainExecutor,
//                extensionSessionStateCallback
//        )
//        cameraDevice?.createExtensionSession(extensionConfiguration)

    }


    /**
    This method will start the still capture request.
     */
    private fun startStillCaptureRequest(){

        captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder?.addTarget(imageReader?.surface!!)
        val rotation = windowManager.defaultDisplay.rotation
        captureRequestBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, orientations.get(rotation))

        val stillCaptureCallback =
            object : CameraCaptureSession.CaptureCallback(){
                override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                }
            }
        previewCaptureSession?.capture(captureRequestBuilder!!.build(), stillCaptureCallback, null)
    }

    private fun transformImage(width: Int, height: Int){
        if(textureView == null || previewSize == null)return

        var matrix : Matrix = Matrix()
        val rotation = windowManager.defaultDisplay.rotation

        val textureRectF : RectF = RectF(0F, 0F, width.toFloat(), height.toFloat())
        val previewRectF : RectF = RectF(0F, 0F, previewSize.width.toFloat(), previewSize.height.toFloat())

        val centerX = textureRectF.centerX()
        val centerY = textureRectF.centerY()

        if(rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270){
            previewRectF.offset(centerX - previewRectF.centerX(), centerY - previewRectF.centerY())
            matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL)

            val scale = Math.max(width.toFloat() / previewSize.width, height.toFloat() / previewSize.height)

            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90F * (rotation - 2), centerX, centerY)

        }
        textureView!!.setTransform(matrix)
    }

    private fun rotateLens(){

        cameraDevice!!.close()
        stopBackgroundThread()

        cameraDevice = null
        cameraId = null


        if(lensFacing == CameraCharacteristics.LENS_FACING_BACK){
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT
        }else{
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        }
        startBackgroundThread()
        setupCamera(textureView!!.width, textureView!!.height)
        connectCamera()
    }

    private fun closeCamera(){
        cameraDevice?.close()
        cameraDevice = null
    }
    
    override protected fun onResume() {
        super.onResume()
        startBackgroundThread()

        if(textureView?.isAvailable == true){
            setupCamera(textureView!!.width, textureView!!.height)
            transformImage(textureView!!.width, textureView!!.height)
            connectCamera()
        }else{
            textureView?.surfaceTextureListener = surfaceTextureListener
        }
    }

    override protected fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun sensorToDeviceOrientation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int) : Int{
        ORIENTATIONS?.append(Surface.ROTATION_0, 0)
        ORIENTATIONS?.append(Surface.ROTATION_90, 90)
        ORIENTATIONS?.append(Surface.ROTATION_180, 180)
        ORIENTATIONS?.append(Surface.ROTATION_270, 270)

        val sensorOrientation  = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val deviceCurrOrientation = ORIENTATIONS?.get(deviceOrientation)?: 90

        if (sensorOrientation != null) {
            return (sensorOrientation.toInt() + deviceCurrOrientation.toInt() + 360) % 360
        }
        return 0
    }

    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("MSCamera2SampleApp")
        backgroundHandlerThread!!.start()
        backgroundHandler = Handler(backgroundHandlerThread!!.looper)
    }

    private fun stopBackgroundThread(){
        backgroundHandlerThread?.quitSafely()
        try{
            backgroundHandlerThread?.join()
            backgroundHandlerThread = null
            backgroundHandler = null
        }catch (e: InterruptedException){
            e.printStackTrace()
        }

    }

    /**
    This method will lock teh focus and invoke previewCaptureSession, when takePhoto button is clicked.
     */
    private fun lockFocus(){
        captureState = STATE_WAIT_LOCK
        captureRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START
        )
        previewCaptureSession?.capture(
                captureRequestBuilder!!.build(),
                previewCaptureCallbacks,
                backgroundHandler
        )
    }

    
    /**
    This method will check the requested permission are granted or not.
     */
    private fun allPermissionGranted(): Boolean {
        for (permission in REQUIRED_PERMISSION) {
            if (ActivityCompat.checkSelfPermission(
                            baseContext,
                            permission
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                setupCamera(textureView!!.width, textureView!!.height)
            } else {
                Toast.makeText(this, "Permission not Granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object Constants {
        const val TAG = "camera2"
        const val FILE_NAME_FORMAT = "yy-MM-dd-HH-mm-ss-ss.jpg"
        const val REQUEST_CODE_PERMISSIONS = 123
        val REQUIRED_PERMISSION = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    // EXTRAS
    private  fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int,
                                   textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size? {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough: MutableList<Size> = ArrayList()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough: MutableList<Size> = ArrayList()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * h / w) {
                if (option.width >= textureViewWidth &&
                        option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        return when {
            bigEnough.size > 0 -> {
                Collections.min(bigEnough, CompareSizesByArea())
            }
            notBigEnough.size > 0 -> {
                Collections.max(notBigEnough, CompareSizesByArea())
            }
            else -> {
                Log.e(TAG, "Couldn't find any suitable preview size")
                choices[0]
            }
        }
    }

    internal class CompareSizesByArea : Comparator<Size?> {
        override fun compare(lhs: Size?, rhs: Size?): Int {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(lhs!!.width.toLong() * lhs.height -
                    rhs!!.width.toLong() * rhs.height)
        }
    }

}


