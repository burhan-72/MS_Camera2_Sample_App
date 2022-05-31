package com.burhan.mscamera2sampleapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.os.Build.VERSION
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.burhan.mscamera2sampleapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Math.abs
import java.lang.Math.max
import java.util.*
import java.util.Arrays.asList
import java.util.stream.Collectors
import android.hardware.camera2.params.OutputConfiguration as OutputConfiguration1


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var textureView : TextureView? = null
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

    private val STATE_PEEVIEW = 0
    private val STATE_WAIT_LOCK = 1
    private var captureState = STATE_PEEVIEW

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
        textureView = binding.viewFinder
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

        setContentView(binding.root)

        takePhotoBtn!!.setOnClickListener(){
            lockFocus()
        }

        rotateCamera!!.setOnClickListener(){
            rotateLens()
        }

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
            startPreview()
        }

        override fun onError(camera: CameraDevice, p1: Int) {
            camera.close()
            Toast.makeText(this@MainActivity, "Camera Connection Error!!", Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            Toast.makeText(this@MainActivity,"Camera Connection Disconnected!!",Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onClosed(camera: CameraDevice) {
            super.onClosed(camera)
//            Toast.makeText(this@MainActivity, "Camera Connection Closed!!", Toast.LENGTH_SHORT).show()

        }
    }


    /**
    A callback for configuring capture sessions from a camera.
    This is needed to check if the camera session is configured and ready to show a preview.
     */
    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {

        override fun onConfigured(captureSession: CameraCaptureSession) {
            previewCaptureSession = captureSession
            previewCaptureSession?.setRepeatingRequest(
                captureRequestBuilder?.build()!!,
                null,
                backgroundHandler
            )
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
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
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

//    private val captureExtensionCallbacks = @RequiresApi(31)
//        object : CameraExtensionSession.ExtensionCaptureCallback(){
//        override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
//            super.onCaptureStarted(session, request, timestamp)
//        }
//
//        override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
//            super.onCaptureFailed(session, request)
//        }
//    }

//    private val extensionSessionStateCallback = @RequiresApi(31)
//        object : CameraExtensionSession.StateCallback(){
//        override fun onConfigured(session: CameraExtensionSession) {
//            cameraExtensionSession = session
//            val surfaceTexture = textureView?.surfaceTexture
//            val width = textureView?.width
//            val height = textureView?.height
//            surfaceTexture?.setDefaultBufferSize(textureView!!.width, textureView!!.height)
//            val previewSurface = Surface(surfaceTexture)
//
//            try{
//                val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
//                captureRequestBuilder?.addTarget(previewSurface)
//                val captureRequest = captureRequestBuilder?.build()
//
//                cameraExtensionSession.setRepeatingRequest(captureRequest!!, mainExecutor, captureExtensionCallbacks)
//
//            }catch(e : CameraAccessException){
//
//            }
//        }
//
//        override fun onConfigureFailed(session: CameraExtensionSession) {
//        }
//    }


    /**
    This callback returns an image when CameraCaptureSession completes capture.
    We need to set this against ImageReader before capturing a still picture.
     */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
            if (reader != null) {
                val status = Environment.getExternalStorageState()
                if (status != Environment.MEDIA_MOUNTED) {
                    Toast.makeText(
                        applicationContext,
                        "your SD card is not available",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                val filePath: File = File(Environment.DIRECTORY_PICTURES)
                val dir = File(filePath.absolutePath + "/MSCamera2SampleApp")

                if(!(dir.exists())){
                    dir.mkdir()
                }

                val image = reader.acquireNextImage()
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                image.close()

                val picturePath = System.currentTimeMillis().toString() + ".jpg"
                val imgFile = File(dir, picturePath)
                val uri: Uri = Uri.fromFile(imgFile)
                try {
                    val fileOutputStream: FileOutputStream = FileOutputStream(imgFile)
                    fileOutputStream.write(data)
                    fileOutputStream.close()
                    Toast.makeText(this, "Photo saved!!", Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Toast.makeText(this, "Error in saving!!", Toast.LENGTH_SHORT).show()
                    Log.e("Image Saving", "Error while saving Image: $e" )
                }
            }
        }


    /**
    This method setup the camera by selecting the cameraDevice as per the useCase.
     */
    private fun setupCamera(width: Int, height: Int){
        val cameraManager : CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for(cameraid : String in cameraManager.cameraIdList){
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraid)
            if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) != lensFacing){
                continue
            }
            previewSize = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.height * it.width }!!
            cameraId = cameraid

//            val jpegSize : Array<Size>? =  cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(
//                ImageFormat.JPEG
//            )

//            var currWidth = width
//            var currHeight = height
//
//            if (jpegSize != null) {
//                for (size in jpegSize){
//                    currWidth = max(currWidth, size.width)
//                    currHeight = max(currHeight, size.height)
//
//                }
//            }

            imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 1)
            imageReader!!.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            return
        }
    }

    
    /**
    This method check the camera permission and if granted open the camera.
     */
    private fun connectCamera(){
        val cameraManager : CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                Constants.REQUIRED_PERMISSION,
                Constants.REQUEST_CODE_PERMISSIONS
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

            matrix.postScale(scale, scale, centerX,centerY)
            matrix.postRotate(90F*(rotation-2), centerX, centerY)

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
        for (permission in Constants.REQUIRED_PERMISSION) {
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
        if (requestCode == Constants.REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                setupCamera(textureView!!.width, textureView!!.height)
            } else {
                Toast.makeText(this, "Permission not Granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object Constants {
        const val TAG = "camera2"
        const val FILE_NAME_FORMAT = "yy-MM-dd-HH-mm-ss-sss"
        const val REQUEST_CODE_PERMISSIONS = 123
        val REQUIRED_PERMISSION = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }
}
