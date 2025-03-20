package com.example.cameraframework

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.RequiresApi

class UnityBridge private constructor(private val context: Context) {
    private val cameraFramework: CameraFramework = CameraFramework.sharedManager(context)

    interface DelegateCallbackFunction {
        fun onNewDataFrame(frameData: FloatArray?)
    }

    companion object {
        @JvmStatic
        private var instance: UnityBridge? = null

        @JvmStatic
        private var delegate: DelegateCallbackFunction? = null

        @JvmStatic
        private var textureView:TextureView? = null

        @JvmStatic
        fun initialize(context: Context) {
            if (instance == null) {
                // Use applicationContext to avoid memory leaks
                instance = UnityBridge(context)
                Log.d("UnityBridge", "Initialized with application context")
            } else {
                Log.d("UnityBridge", "Already initialized")
            }
        }

        @JvmStatic
        fun getInstance(): UnityBridge? {
            if (instance == null) {
                Log.e("UnityBridge", "UnityBridge not initialized! Call initialize() first")
            }
            return instance
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        @JvmStatic
        fun startCamera(backCam: Boolean, minFps: Int, maxFps: Int): Boolean {
            val instance = getInstance() ?: return false
            return instance.cameraFramework.startCamera(backCam, minFps, maxFps)
        }

        @JvmStatic
        fun setupFramework(perfMode: Boolean): Boolean {
            val instance = getInstance() ?: return false
            return instance.cameraFramework.setupVisionFrameWork(perfMode)
        }

        @JvmStatic
        fun startRecognition(samplingRate: Int): Boolean {
            val instance = getInstance() ?: return false
            return instance.cameraFramework.startRecognition(samplingRate)
        }

        @JvmStatic
        fun setDelegate(callback: DelegateCallbackFunction?) {
            val instance = getInstance() ?: return
            delegate = callback
            instance.cameraFramework.setDelegate(object : DataDelegate {
                override fun newDataFrameAvailable(frameData: FloatArray) {
                    if (delegate != null) {
                        delegate!!.onNewDataFrame(frameData)
                    } else {
                        Log.e("UnityBridge", "Data delegate is null")
                    }
                }
            })
        }

        @JvmStatic
        fun discardDelegate() {
            val instance = getInstance() ?: return
            delegate = null
            instance.cameraFramework.discardPoseDelegate()
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        @JvmStatic
        fun swapCamera(): Boolean {
            val instance = getInstance() ?: return false
            return instance.cameraFramework.swapInputCamera()
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        @JvmStatic
        fun destroyAll() {
            val instance = getInstance() ?: return
            instance.cameraFramework.destroyAll()
//            instance = null
            delegate = null
            Log.d("UnityBridge", "All resources destroyed")
        }

        @RequiresApi(Build.VERSION_CODES.M)
        @JvmStatic
        fun requestCameraPermission() {
            val instance = getInstance() ?: return
            instance.cameraFramework.requestCameraPermission()
        }

        @JvmStatic
        fun showToast(message: String) {
            val instance = getInstance() ?: return
            Toast.makeText(instance.context, message, Toast.LENGTH_LONG).show()
        }

        @JvmStatic
        fun unlockFrame(): Boolean {
            val instance = getInstance() ?: return false
            return instance.cameraFramework.unlockFrame()
        }

        @JvmStatic
        fun setFrameDelegate(delegate: FrameDelegate) {
            val instance = getInstance() ?: return
            instance.cameraFramework.setFrameDelegate(delegate)
        }

        @JvmStatic
        fun discardFrameDelegate() {
            val instance = getInstance() ?: return
            instance.cameraFramework.discardFrameDelegate()
        }

        @JvmStatic
        fun setPermissionsDelegate(delegate: PermissionDelegate) {
            val instance = getInstance() ?: return
            instance.cameraFramework.setPermissionsDelegate(delegate)
        }

        @JvmStatic
        fun discardPermissionsDelegate() {
            val instance = getInstance() ?: return
            instance.cameraFramework.discardPermissionsDelegate()
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        @JvmStatic
        fun stopCamera() {
            val instance = getInstance() ?: return
            instance.cameraFramework.stopCamera()
        }

        // Helper method to check if the bridge is initialized
        @JvmStatic
        fun isInitialized(): Boolean {
            return instance != null
        }
    }
}