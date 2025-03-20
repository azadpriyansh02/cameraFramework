package com.example.cameraframework

import android.content.Context
import android.util.Log

// Interface to match Unity's callback function for camera frames
interface FrameDelegateCallbackFunction {
    fun onNewFrame(frameData: ByteArray, width: Int, height: Int)
}


class CameraDelegate private constructor() {
    companion object {
        private var instance: CameraDelegate? = null
        private var frameCallback: FrameDelegateCallbackFunction? = null

        fun getInstance(): CameraDelegate {
            if (instance == null) {
                Log.d("CameraDelegate", "Allocating New Frame Delegate")
                instance = CameraDelegate()
            }
            return instance!!
        }

        // This matches the iOS setFrameDelegate function
        fun setFrameDelegate(context: Context, callback: FrameDelegateCallbackFunction) {
            val delegate = getInstance()
            frameCallback = callback

            // Register with the CameraFramework
            CameraFramework.sharedManager(context).setFrameDelegate(object : FrameDelegate {
                override fun newImageFrameAvailable(framePointer: Any, width: Int, height: Int) {
                    if (frameCallback != null && framePointer is ByteArray) {
                        frameCallback!!.onNewFrame(framePointer, width, height)
                    } else {
                        Log.e(
                            "CameraDelegate",
                            "Frame callback is null or invalid frame pointer type"
                        )
                    }
                }
            })
        }

        // This matches the iOS releaseFrame function
        fun releaseFrame(context: Context): Boolean {
            return CameraFramework.sharedManager(context).unlockFrame()
        }

        // This matches the iOS DisposeCameraDelegate function
        fun disposeCameraDelegate(context: Context) {
            instance = null
            frameCallback = null
            CameraFramework.sharedManager(context).discardFrameDelegate()
            Log.d("CameraDelegate", "Disposed Camera Delegate")
        }
    }
}

// Create a JNI-friendly wrapper for Unity to call
class CameraBridge {
    companion object {
        // Static reference to hold the callback instance
        private var callbackInstance: FrameDelegateCallbackFunction? = null

        // Set up the frame delegate with a callback
        @JvmStatic
        fun setFrameDelegate(context: Context, listener: FrameDelegateCallbackFunction) {
            callbackInstance = listener
            CameraDelegate.setFrameDelegate(context, listener)
        }

        // Release the current frame
        @JvmStatic
        fun releaseFrame(context: Context): Boolean {
            return CameraDelegate.releaseFrame(context)
        }

        // Clean up the frame delegate
        @JvmStatic
        fun disposeCameraDelegate(context: Context) {
            callbackInstance = null
            CameraDelegate.disposeCameraDelegate(context)
        }
    }
}