package com.spoon.dualcamera;

import android.app.Activity;
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
import org.json.JSONObject;
import android.graphics.Color;
import androidx.fragment.app.Fragment;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.CameraInfo;
import java.util.List;
import com.google.common.util.concurrent.ListenableFuture;

public class DualCameraPreview extends CordovaPlugin {
    private static final String FRAGMENT_TAG = "DualCameraPreviewFragment";
    private static int previewContainerId=-1;
    private static CallbackContext videoCallbackContext;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext){
        try {
            switch(action){
                case "deviceSupportDualMode":
                    deviceSupportDualMode(callbackContext);
                    return true;

                case "enable":
                    enable(callbackContext);
                    return true;

                case "disable":
                    disable(callbackContext);
                    return true;

                case "capture":
                    capture(callbackContext);
                    return true;

                case "initVideoCallback":
                    initVideoCallback(callbackContext);
                    return true;

                case "startVideoCapture":
                    startVideoCapture(args, callbackContext);
                    return true;

                case "stopVideoCapture":
                    stopVideoCapture(callbackContext);
                    return true;

                default:
                    return false;

            }
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            return true;
        }
    }

    private void enable(final CallbackContext callbackContext) {
        Activity activity = cordova.getActivity();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
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

                DualCameraPreviewFragment dualFragment = new DualCameraPreviewFragment(callbackContext);

                fragmentManager.beginTransaction()
                        .replace(previewContainerId,dualFragment,FRAGMENT_TAG)
                        .commitAllowingStateLoss();
            }
        });
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
                        View container = activity.findViewById(previewContainerId);

                        if (container != null && container.getParent()!=null ) {
                            ((ViewGroup) container.getParent()).removeView(container);
                        }

                        previewContainerId = -1;

                    }

                    callbackContext.sendPluginResult(
                            new PluginResult(PluginResult.Status.OK, true)
                    );

                } catch (Exception e) {
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void deviceSupportDualMode(final CallbackContext callbackContext) {
        final Activity activity = cordova.getActivity();
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
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void initVideoCallback(CallbackContext callbackContext) {
        videoCallbackContext = callbackContext;
        try {
            JSONObject result = new JSONObject();
            result.put("videoCallbackInitialized", true);

            PluginResult pluginResult = new PluginResult(
                    PluginResult.Status.OK,
                    result
            );

            pluginResult.setKeepCallback(true);
            videoCallbackContext.sendPluginResult(pluginResult);

        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
        }
    }

    private void startVideoCapture(JSONArray args, CallbackContext callbackContext) {
        try {
            JSONObject options = args.optJSONObject(0);

            if (options == null) {
                throw new IllegalArgumentException("Options are required");
            }

            int videoDurationMs = options.optInt("videoDurationMs", 3000);

            cordova.getActivity().runOnUiThread(() -> {
                try {
                    Activity activity = cordova.getActivity();

                    if (!(activity instanceof FragmentActivity)) {
                        callbackContext.error("MainActivity must extend FragmentActivity or AppCompatActivity");
                        return;
                    }

                    FragmentActivity fragmentActivity = (FragmentActivity) activity;
                    FragmentManager fragmentManager = fragmentActivity.getSupportFragmentManager();

                    Fragment fragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG);

                    DualCameraPreviewFragment dualCameraFragment =
                            (DualCameraPreviewFragment) fragment;

                    if (dualCameraFragment == null) {
                        callbackContext.error("Dual camera fragment is not initialized");
                        return;
                    }

                    dualCameraFragment.startVideoCapture(
                            videoDurationMs,
                            new DualCameraPreviewFragment.VideoCallback() {
                                @Override
                                public void onStart() {
                                    try {
                                        JSONObject result = new JSONObject();
                                        result.put("recording", true);

                                        PluginResult pluginResult = new PluginResult(
                                                PluginResult.Status.OK,
                                                result
                                        );

                                        pluginResult.setKeepCallback(true); // need to verify
                                        videoCallbackContext.sendPluginResult(pluginResult);

                                    } catch (JSONException e) {
                                        videoCallbackContext.error(e.getMessage());
                                    }
                                }

                                @Override
                                public void onProcessing() {
                                    try {
                                        JSONObject result = new JSONObject();
                                        result.put("processing", true);

                                        PluginResult pluginResult = new PluginResult(
                                                PluginResult.Status.OK,
                                                result
                                        );

                                        pluginResult.setKeepCallback(true);
                                        videoCallbackContext.sendPluginResult(pluginResult);

                                    } catch (JSONException e) {
                                        videoCallbackContext.error(e.getMessage());
                                    }
                                }

                                @Override
                                public void onStop(String nativePath, String thumbnailNativePath) {
                                    try {

                                        JSONObject result = new JSONObject();
                                        result.put("recording", false);
                                        result.put("processed", true);
                                        result.put("thumbnail", thumbnailNativePath);
                                        result.put("nativePath", nativePath);

                                        PluginResult pluginResult = new PluginResult(
                                                PluginResult.Status.OK,
                                                result
                                        );

                                        pluginResult.setKeepCallback(true);
                                        videoCallbackContext.sendPluginResult(pluginResult);

                                    } catch (JSONException e) {
                                        videoCallbackContext.error(e.getMessage());
                                    }
                                }

                                @Override
                                public void onError(String error) {
                                    PluginResult pluginResult = new PluginResult(
                                            PluginResult.Status.ERROR,
                                            error
                                    );

                                    pluginResult.setKeepCallback(true);
                                    videoCallbackContext.sendPluginResult(pluginResult);
                                }
                            }
                    );

                } catch (Exception e) {
                    callbackContext.error(e.getMessage());
                }
            });

        } catch (Exception e) {
            callbackContext.error(e.getMessage());
        }
    }

    private void stopVideoCapture(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            try {
                Activity activity = cordova.getActivity();

                if (!(activity instanceof FragmentActivity)) {
                    callbackContext.error("MainActivity must extend FragmentActivity or AppCompatActivity");
                    return;
                }

                FragmentActivity fragmentActivity = (FragmentActivity) activity;
                FragmentManager fragmentManager = fragmentActivity.getSupportFragmentManager();

                Fragment fragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG);

                if (!(fragment instanceof DualCameraPreviewFragment)) {
                    callbackContext.error("Dual camera fragment is not initialized");
                    return;
                }

                DualCameraPreviewFragment dualCameraFragment =
                        (DualCameraPreviewFragment) fragment;

                dualCameraFragment.stopVideoCapture();
                callbackContext.success();

            } catch (Throwable t) {
                callbackContext.error(t.getMessage());
            }
        });
    }
}