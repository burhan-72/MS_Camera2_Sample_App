package com.burhan.mscamera2sampleapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import com.burhan.mscamera2sampleapp.databinding.ActivityMainBinding
import java.util.Arrays.asList
import android.hardware.camera2.CameraManager as CameraManager


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
    private var imageSize : Size? = null
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
            takePhoto()
        }

    }

    private fun setupCamera(width: Int, height: Int){
        val cameraManager : CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for(cameraid : String in cameraManager.cameraIdList){
            val cameraCharacteristic = cameraManager.getCameraCharacteristics(cameraid)
            if(cameraCharacteristic.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                continue
            }

            val deviceOrientation : Int = resources.configuration.orientation
            val totalRotation = sensorToDeviceOrientation(cameraCharacteristic, deviceOrientation)
            val swapRotation = totalRotation == 90 || totalRotation ==270



            var rotatedWidth = width
            var rotatedHeight = height

            if(swapRotation){
                rotatedHeight = width
                rotatedWidth = height
            }

//            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice?.id!!)
//            val jpegSize : Array<Size>? =  cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(
//                ImageFormat.JPEG)

//            var width : Int = 480
//            var height : Int = 640
//            if(jpegSize != null && jpegSize.size > 0){
//                width = jpegSize[0].width
//                height = jpegSize[0].height
//
//            }
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            imageReader!!.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)

            cameraId = cameraid
            return
        }
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
            Toast.makeText(this@MainActivity,"Camera Connection Error!!",Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            Toast.makeText(this@MainActivity,"Camera Connection Disconnected!!",Toast.LENGTH_SHORT).show()
            cameraDevice = null
        }

        override fun onClosed(camera: CameraDevice) {
            super.onClosed(camera)
            Toast.makeText(this@MainActivity,"Camera Connection Closed!!",Toast.LENGTH_SHORT).show()
        }
    }

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {

        override fun onConfigured(captureSession: CameraCaptureSession) {
            previewCaptureSession = captureSession
            previewCaptureSession?.setRepeatingRequest(captureRequestBuilder?.build()!!,null, backgroundHandler)
        }
        override fun onConfigureFailed(captureSession : CameraCaptureSession) {
            Toast.makeText(this@MainActivity, "Unable to setup camera preview",Toast.LENGTH_SHORT).show()
        }
    }

    private val onImageAvailableListener = object : ImageReader.OnImageAvailableListener{
            override fun onImageAvailable(reader : ImageReader?) {
//                TODO("Not yet implemented")

            }
        }

    private val previewCaptureCallbacks = object : CameraCaptureSession.CaptureCallback(){

            var afState :Int? = null
            private  fun process(captureResult : CaptureResult){
                when(captureState){
                    STATE_PEEVIEW -> {}
                    STATE_WAIT_LOCK ->{
                        captureState = STATE_PEEVIEW
                        afState  = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                        if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED){
                            Toast.makeText(this@MainActivity, "AF LOCKED", Toast.LENGTH_SHORT).show()
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


    private fun closeCamera(){
        cameraDevice?.close()
        cameraDevice = null
    }

    private val captureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                super.onCaptureFailed(session, request, failure)
            }
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
        }catch (e : InterruptedException){
            e.printStackTrace()
        }

    }

    private fun sensorToDeviceOrientation(cameraCharacteristics: CameraCharacteristics, deviceOrientation : Int) : Int{
        ORIENTATIONS?.append(Surface.ROTATION_0,0)
        ORIENTATIONS?.append(Surface.ROTATION_90,90)
        ORIENTATIONS?.append(Surface.ROTATION_180,180)
        ORIENTATIONS?.append(Surface.ROTATION_270,270)

        val sensorOrientation  = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val deviceCurrOrientation = ORIENTATIONS?.get(deviceOrientation)?: 90

        if (sensorOrientation != null) {
            return (sensorOrientation.toInt() + deviceCurrOrientation.toInt() + 360) % 360
        }
        return 0

    }

    private fun connectCamera(){
        val cameraManager : CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,Constants.REQUIRED_PERMISSION,Constants.REQUEST_CODE_PERMISSIONS)
            return
        }

        cameraManager.openCamera(cameraId!!,cameraStateCallback,backgroundHandler)
    }

    private fun lockFocus(){
        captureState = STATE_WAIT_LOCK
        captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        previewCaptureSession?.capture(captureRequestBuilder!!.build(), previewCaptureCallbacks, backgroundHandler)
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

    private fun takePhoto(){
        lockFocus()

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
        val REQUIRED_PERMISSION = arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)

    }
}