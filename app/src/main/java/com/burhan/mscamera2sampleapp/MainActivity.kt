package com.burhan.mscamera2sampleapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.burhan.mscamera2sampleapp.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.Arrays.asList


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var textureView : TextureView? = null
    private var takePhotoBtn : ImageButton? = null
    private var cameraDevice : CameraDevice? = null
    private var cameraId : String? = null
    private var backgroundHandler : Handler? = null
    private var backgroundHandlerThread : HandlerThread? = null
    private var ORIENTATIONS : SparseIntArray? = null
    private var captureRequestBuilder : CaptureRequest.Builder? = null
    private var previewCaptureSession : CameraCaptureSession? = null
    private var imageReader : ImageReader? = null


    private val STATE_PEEVIEW = 0
    private val STATE_WAIT_LOCK = 1
    private var captureState = STATE_PEEVIEW



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        textureView = binding.viewFinder
        takePhotoBtn = binding.takePhotoBtn

        setContentView(binding.root)

        takePhotoBtn!!.setOnClickListener(){
            lockFocus()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            setupCamera(width, height)
            connectCamera()
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {

        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return false
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
//            Toast.makeText(this@MainActivity,"Camera Connection made!!",Toast.LENGTH_SHORT).show()
        }

        override fun onError(camera: CameraDevice, p1: Int) {
            camera.close()
            Toast.makeText(this@MainActivity, "Camera Connection Error!!", Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            Toast.makeText(
                this@MainActivity,
                "Camera Connection Disconnected!!",
                Toast.LENGTH_SHORT
            ).show()
            cameraDevice = null
        }

        override fun onClosed(camera: CameraDevice) {
            super.onClosed(camera)
            Toast.makeText(this@MainActivity, "Camera Connection Closed!!", Toast.LENGTH_SHORT).show()
        }
    }

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

    private val onImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader ->
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
                val filePath: File = File(Environment.DIRECTORY_PICTURES).absoluteFile
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

    private fun setupCamera(width: Int, height: Int){
        val cameraManager : CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for(cameraid : String in cameraManager.cameraIdList){
            val cameraCharacteristic = cameraManager.getCameraCharacteristics(cameraid)
            if(cameraCharacteristic.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                continue
            }

            cameraId = cameraid
            val jpegSize : Array<Size>? =  cameraCharacteristic.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(
                ImageFormat.JPEG
            )

            var currWidth = width
            var currHeight = height

            if(jpegSize != null && jpegSize.size > 0){
                currWidth = jpegSize[0].width
                currHeight = jpegSize[0].height

            }
            imageReader = ImageReader.newInstance(currWidth, currHeight, ImageFormat.JPEG, 1)
            imageReader!!.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)


            return
        }
    }

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

    private fun startPreview(){
        val surfaceTexture = textureView?.surfaceTexture
        surfaceTexture?.setDefaultBufferSize(textureView!!.width, textureView!!.height)
        val previewSurface = Surface(surfaceTexture)

        captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder?.addTarget(previewSurface)

        cameraDevice?.createCaptureSession(
            asList(previewSurface, imageReader?.surface),
            captureStateCallback,
            backgroundHandler
        )

    }

    private fun startStillCaptureRequest(){
        captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder?.addTarget(imageReader?.surface!!)

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

    private fun closeCamera(){
        cameraDevice?.close()
        cameraDevice = null
    }

    override protected fun onResume() {
        super.onResume()
        startBackgroundThread()

        if(textureView?.isAvailable == true){
            setupCamera(textureView!!.width, textureView!!.height)
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

    private fun sensorToDeviceOrientation(
        cameraCharacteristics: CameraCharacteristics,
        deviceOrientation: Int
    ) : Int
    {
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
        backgroundHandlerThread?.interrupt()
        try{
            backgroundHandlerThread?.join()
            backgroundHandlerThread = null
            backgroundHandler = null
        }catch (e: InterruptedException){
            e.printStackTrace()
        }

    }

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

    object Constants {
        const val TAG = "cameraX"
        const val FILE_NAME_FORMAT = "yy-MM-dd-HH-mm-ss-sss"
        const val REQUEST_CODE_PERMISSIONS = 123
        val REQUIRED_PERMISSION = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

    }
}