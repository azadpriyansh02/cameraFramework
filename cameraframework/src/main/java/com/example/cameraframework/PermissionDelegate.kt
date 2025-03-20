package com.example.cameraframework

import android.content.Context
import android.util.Log

// Interface that matches Unity's callback function
interface PermissionDelegateCallbackFunction {
    fun onPermissionResult(statusCode: Int)
}

class PermissionsDelegate private constructor() {
    companion object {
        private var instance: PermissionsDelegate? = null
        private var permissionsCallback: PermissionDelegateCallbackFunction? = null

        fun getInstance(): PermissionsDelegate {
            if (instance == null) {
                Log.d("PermissionsDelegate", "Allocating New Permissions Delegate")
                instance = PermissionsDelegate()
            }
            return instance!!
        }

        // This matches the iOS setPermissionsDelegate function
        fun setPermissionsDelegate(context: Context, callback: PermissionDelegateCallbackFunction) {
            val delegate = getInstance()
            permissionsCallback = callback

            // Register with the CameraFramework
            CameraFramework.sharedManager(context)
                .setPermissionsDelegate(object : PermissionDelegate {
                    override fun permissionResultReturned(statusCode: Int) {
                        if (permissionsCallback != null) {
                            permissionsCallback!!.onPermissionResult(statusCode)
                        } else {
                            Log.e("PermissionsDelegate", "Permissions callback is null")
                        }
                    }
                })
        }

        // This matches the iOS DisposePermissionDelegate function
        fun disposePermissionDelegate(context: Context) {
            instance = null
            permissionsCallback = null
            CameraFramework.sharedManager(context).discardPermissionsDelegate()
            Log.d("PermissionsDelegate", "Disposed Permissions Delegate")
        }
    }
}

// Create a JNI-friendly wrapper for Unity to call
class PermissionsBridge {
    companion object {
        // Static reference to hold the callback instance
        private var callbackInstance: PermissionDelegateCallbackFunction? = null

        // Set up the permissions delegate with a callback
        @JvmStatic
        fun setPermissionsDelegate(context: Context, listener: PermissionDelegateCallbackFunction) {
            callbackInstance = listener
            PermissionsDelegate.setPermissionsDelegate(context, listener)
        }

        // Clean up the permissions delegate
        @JvmStatic
        fun disposePermissionDelegate(context: Context) {
            callbackInstance = null
            PermissionsDelegate.disposePermissionDelegate(context)
        }

        // Request camera permissions
        @JvmStatic
        fun requestCameraPermission(context: Context) {
            CameraFramework.sharedManager(context).requestCameraPermission()
        }
    }
}