package com.spoon.dualcamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import android.widget.FrameLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import android.graphics.Color;
import androidx.fragment.app.Fragment;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.CameraInfo;
import java.util.List;
import com.google.common.util.concurrent.ListenableFuture;


public class DualCameraPreview extends CordovaPlugin {
    private static final String TAG = "DualCameraPreview";
    private static final String ACTION_ENABLE = "enable";
    private static final String FRAGMENT_TAG = "DualCameraPreviewFragment";
    private static final String ACTION_DEVICE_SUPPORT_DUAL_MODE="deviceSupportDualMode";
    private static final String ACTION_DISABLE="disable";
    private static final String ACTION_CAPTURE="capture";
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static int previewContainerId=-1;

    private static CallbackContext pendingEnableCallback;
    @Override
    public boolean execute(
            String action,
            JSONArray args,
            CallbackContext callbackContext
    ) throws JSONException {

        if (ACTION_ENABLE.equals(action)) {
            enable(callbackContext);
            return true;
        }

        if (ACTION_DEVICE_SUPPORT_DUAL_MODE.equals(action)) {
            deviceSupportDualMode(callbackContext);
            return true;
        }

        if(ACTION_DISABLE.equals(action)){
            disable(callbackContext);
            return true;
        }

        if(ACTION_CAPTURE.equals(action)){
            capture(callbackContext);
            return true;
        }
        return false;
    }


    private void enable(final CallbackContext callbackContext) {
        Activity activity = cordova.getActivity();

        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {

            pendingEnableCallback= callbackContext;

            cordova.requestPermission(
                    this,
                    CAMERA_PERMISSION_REQUEST,
                    Manifest.permission.CAMERA
            );

            return;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                openPreviewFragment(callbackContext);
            }
        });
    }

    @Override
    public void onRequestPermissionResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) throws JSONException {

        Log.d(TAG,"Test on Request Permission Result");
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                if (pendingEnableCallback != null) {
                    enable(pendingEnableCallback);
                    Log.d(TAG,"Test on Request Permission Result ACCEPT");
                    pendingEnableCallback = null;
                }

            } else {
                if (pendingEnableCallback != null) {
                    pendingEnableCallback.error("Camera permission denied");
                    pendingEnableCallback = null;
                }
            }
        }
    }

    private void openPreviewFragment(CallbackContext callbackContext) {
        Activity activity = cordova.getActivity();

        if (!(activity instanceof FragmentActivity)) {
            callbackContext.error("MainActivity must extend FragmentActivity or AppCompatActivity");
            return;
        }

        FragmentActivity fragmentActivity = (FragmentActivity) activity;
        FragmentManager fragmentManager = fragmentActivity.getSupportFragmentManager();

        Fragment existing = fragmentManager.findFragmentByTag(FRAGMENT_TAG);
        if (existing != null && previewContainerId != -1) {
            callbackContext.success("Dual camera preview already enabled");
            return;
        }

        ViewGroup root = activity.findViewById(android.R.id.content);

        previewContainerId = View.generateViewId();
        FrameLayout container = new FrameLayout(activity);
        container.setId(previewContainerId);

        container.setClickable(false);
        container.setFocusable(false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );

        root.addView(container, 0, params);

        View cordovaWebView = webView.getView();
        cordovaWebView.setBackgroundColor(Color.TRANSPARENT);

        fragmentManager.beginTransaction()
                .replace(previewContainerId, new DualCameraPreviewFragment(),FRAGMENT_TAG)
                .commitAllowingStateLoss();

        callbackContext.success("Dual camera preview enabled");
    }

    private void disable(final CallbackContext callbackContext) {
        final Activity activity = cordova.getActivity();

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!(activity instanceof FragmentActivity)) {
                        callbackContext.error("MainActivity must extend FragmentActivity or AppCompatActivity");
                        return;
                    }

                    FragmentActivity fragmentActivity = (FragmentActivity) activity;
                    FragmentManager fragmentManager = fragmentActivity.getSupportFragmentManager();

                    Fragment fragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG);

                    if (fragment != null) {
                        fragmentManager.beginTransaction()
                                .remove(fragment)
                                .commitAllowingStateLoss();
                    }

                    if (previewContainerId != -1) {
                        Log.d("CameraX","previewContainerId" + previewContainerId);
                        View container = activity.findViewById(previewContainerId);

                        if (container != null) {
                            Log.d("CameraX", "CameraX test1");
                            ViewGroup parent = (ViewGroup) container.getParent();

                            if (parent != null) {
                                Log.d("CameraX", "CameraX test2");
                                parent.removeView(container);
                            }
                        }

                        previewContainerId = -1;

                        Log.d("CameraX","previewContainerId" + previewContainerId);

                    }

                    callbackContext.success("Dual camera preview disabled");

                } catch (Exception e) {
                    Log.e(TAG, "Failed to disable dual camera preview", e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void deviceSupportDualMode(final CallbackContext callbackContext) {
        final Activity activity = cordova.getActivity();

        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {
            Log.d("CameraX", "Camera permission not granted");

            callbackContext.sendPluginResult(
                    new PluginResult(PluginResult.Status.OK, false)
            );
            return;
        }

        final  ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(activity);

        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = future.get();

                    List<List<CameraInfo>> concurrentCameraInfos =
                            cameraProvider.getAvailableConcurrentCameraInfos();

                    boolean supportsDualCamera =
                            concurrentCameraInfos != null &&
                                    !concurrentCameraInfos.isEmpty();

                    callbackContext.sendPluginResult(
                            new PluginResult(PluginResult.Status.OK, supportsDualCamera)
                    );

                } catch (Exception e) {
                    Log.e(TAG, "Failed to check dual camera support", e);
                    callbackContext.error(e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private void capture(final CallbackContext callbackContext) {
        final Activity activity = cordova.getActivity();

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!(activity instanceof FragmentActivity)) {
                        callbackContext.error("MainActivity must extend FragmentActivity or AppCompatActivity");
                        return;
                    }

                    FragmentActivity fragmentActivity = (FragmentActivity) activity;
                    FragmentManager fragmentManager = fragmentActivity.getSupportFragmentManager();

                    Fragment fragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG);

                    if (fragment == null) {
                        callbackContext.error("Dual camera preview is not enabled");
                        return;
                    }

                    if (!(fragment instanceof DualCameraPreviewFragment)) {
                        callbackContext.error("Invalid dual camera fragment");
                        return;
                    }

                    DualCameraPreviewFragment cameraFragment =
                            (DualCameraPreviewFragment) fragment;

                    cameraFragment.capture(callbackContext);

                } catch (Exception e) {
                    Log.e(TAG, "Capture failed", e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

}

