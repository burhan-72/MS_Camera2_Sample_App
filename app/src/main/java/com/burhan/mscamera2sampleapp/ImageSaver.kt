package com.burhan.mscamera2sampleapp


import android.media.Image
import android.util.Log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal class ImageSaver(
        private val image: Image,

        private val file: File
) : Runnable {

    override fun run() {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(file)
            output.write(bytes)
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        } finally {
            image.close()
            output?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    Log.e(TAG, e.toString())
                }
            }
        }
    }

    companion object {
        /**
         * Tag for the [Log].
         */
        private val TAG = "ImageSaver"
    }
}

