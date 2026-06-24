package com.spoon.dualcamera;

import android.graphics.Bitmap;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import android.os.Handler;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.VideoRecordEvent;
import java.io.File;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.cordova.CallbackContext;
import android.net.Uri;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.media.MediaMetadataRetriever;
import android.view.OrientationEventListener;
import androidx.camera.core.CompositionSettings;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.MirrorMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Matrix;

public class DualCameraPreviewFragment extends Fragment {
    private ProcessCameraProvider cameraProvider;
    private ConcurrentCamera concurrentCamera;
    private CallbackContext enableCallback;
    private static final float frontWidthRatio = 0.30f;
    private static final float frontMarginNdc = 0.15f;
    private OrientationEventListener orientationEventListener;
    private int currentTargetRotation = Surface.ROTATION_0;
    private PreviewView previewView;
    private VideoCapture<Recorder> videoCapture;
    private ExecutorService captureExecutor;
    private Recording activeRecording;
    private boolean videoResultSent = false;
    private boolean stopRequested = false;
    private VideoCallback videoCallback;
        private Preview preview;

    public DualCameraPreviewFragment(CallbackContext callbackContext) {
        this.enableCallback = callbackContext;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull android.view.LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        FrameLayout root = new FrameLayout(requireContext());

        previewView = createPreviewView();

        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        captureExecutor = Executors.newSingleThreadExecutor();
        setupOrientationListener();
        return root;
    }

    private PreviewView createPreviewView() {
        PreviewView previewView = new PreviewView(requireContext());
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        return previewView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        previewView.post(() -> {
            if (previewView.getDisplay() != null) {
                currentTargetRotation = previewView.getDisplay().getRotation();
            }

            startCamera(enableCallback);
        });
    }

    private void startCamera(final CallbackContext callbackContext) {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
            } catch (Exception e) {
                if (callbackContext != null) {
                    callbackContext.error(e.getMessage());
                }
                return;
            }
            bindDualCamera(callbackContext);  //bind the front and back cameras
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private Recorder createRecorder() {
        return new Recorder.Builder()
                .setQualitySelector(
                        QualitySelector.from(
                                Quality.HD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                )
                .build();
    }

    private void bindDualCamera(CallbackContext callbackContext) {
        if (cameraProvider == null) {
            return;
        }

        cameraProvider.unbindAll();

        DualCameraInfo cameras = findFrontBackConcurrentCameras(
                cameraProvider.getAvailableConcurrentCameraInfos()
        );

        if (cameras == null) {
            if (callbackContext != null) {
                callbackContext.error("Front/back concurrent camera is not available");
            }
            return;
        }

        UseCaseGroup useCaseGroup = createDualCameraUseCaseGroup();

        List<ConcurrentCamera.SingleCameraConfig> configs =
                createDualCameraConfigs(cameras, useCaseGroup);

        try {
            concurrentCamera = cameraProvider.bindToLifecycle(configs);

            if (callbackContext != null) {
                callbackContext.success();
            }

        } catch (RuntimeException e) {
            videoCapture = null;
            concurrentCamera = null;

            if (callbackContext != null) {
                callbackContext.error(e.getMessage());
            }

        }

    }

    private static class DualCameraInfo {
        final CameraInfo frontCameraInfo;
        final CameraInfo backCameraInfo;

        DualCameraInfo(CameraInfo frontCameraInfo, CameraInfo backCameraInfo) {
            this.frontCameraInfo = frontCameraInfo;
            this.backCameraInfo = backCameraInfo;
        }
    }

    private DualCameraInfo findFrontBackConcurrentCameras(
            List<List<CameraInfo>> cameraCombinations
    ) {
        for (List<CameraInfo> combination : cameraCombinations) {
            CameraInfo frontCamera = null;
            CameraInfo backCamera = null;

            for (CameraInfo cameraInfo : combination) {
                Integer lensFacing = cameraInfo.getLensFacing();

                if (lensFacing == null) {
                    continue;
                }

                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    frontCamera = cameraInfo;
                } else if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    backCamera = cameraInfo;
                }
            }

            if (frontCamera != null && backCamera != null) {
                return new DualCameraInfo(frontCamera, backCamera);
            }
        }

        return null;
    }

    private List<ConcurrentCamera.SingleCameraConfig> createDualCameraConfigs(
            DualCameraInfo cameras,
            UseCaseGroup useCaseGroup
    ) {
        List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();

        configs.add(new ConcurrentCamera.SingleCameraConfig(
                cameras.backCameraInfo.getCameraSelector(),
                useCaseGroup,
                createBackFullScreenComposition(),
                this
        ));

        configs.add(new ConcurrentCamera.SingleCameraConfig(
                cameras.frontCameraInfo.getCameraSelector(),
                useCaseGroup,
                createFrontPipComposition(),
                this
        ));

        return configs;
    }

    private CompositionSettings createBackFullScreenComposition() {
        return new CompositionSettings.Builder()
                .setAlpha(1.0f)
                .setOffset(0.0f, 0.0f)
                .setScale(1.0f, 1.0f)
                .build();
    }
    private CompositionSettings createFrontPipComposition() {
        float pipScale = frontWidthRatio;

        return new CompositionSettings.Builder()
                .setAlpha(1.0f)
                .setOffset(
                        -1.0f + pipScale + frontMarginNdc,
                        1.0f - pipScale - frontMarginNdc
                )
                .setScale(pipScale, pipScale)
                .build();
    }
    private UseCaseGroup createDualCameraUseCaseGroup() {
        ResolutionSelector resolutionSelector =
                new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        )
                        .build();

        preview =
                new Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(getPreviewDisplayRotation())
                        .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        videoCapture =
                new VideoCapture.Builder<>(createRecorder())
                        .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                        .build();

        videoCapture.setTargetRotation(currentTargetRotation);

        return new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture)
                .build();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }

        concurrentCamera = null;
        previewView = null;
        videoCapture = null;

        if (captureExecutor != null) {
            captureExecutor.shutdown();
        }
    }

    private int getPreviewDisplayRotation() {
        if (previewView != null && previewView.getDisplay() != null) {
            return previewView.getDisplay().getRotation();
        }

        return Surface.ROTATION_0;
    }


    public void capture(final CallbackContext callbackContext) {
        if (!isAdded() || getContext() == null) {
            callbackContext.error("Fragment is not attached");
            return;
        }

        if (previewView == null) {
            callbackContext.error("Preview is not ready");
            return;
        }

        requireActivity().runOnUiThread(() -> {
            try {
                Bitmap bitmap = previewView.getBitmap();

                if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                    callbackContext.error("Preview frame is not ready yet");
                    return;
                }

                Bitmap finalBitmap = rotateBitmapForFinalImage(bitmap);

                File finalFile = new File(
                        requireContext().getFilesDir(),
                        UUID.randomUUID().toString() + ".jpg"
                );

                saveBitmapAsync(finalBitmap, finalFile, callbackContext);

            } catch (Exception e) {
                callbackContext.error(e.getMessage());
            }
        });
    }

    private void saveBitmapAsync(final Bitmap bitmap, final File file, final CallbackContext callbackContext) {
        captureExecutor.execute(() -> {
            try {
                try (OutputStream outputStream = new java.io.FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
                    outputStream.flush();
                }

                bitmap.recycle();

                final String imageNativePath = Uri.fromFile(file).toString();

                requireActivity().runOnUiThread(() ->
                        callbackContext.success(imageNativePath)
                );

            } catch (Exception e) {
                bitmap.recycle();

                requireActivity().runOnUiThread(() ->
                        callbackContext.error(e.getMessage())
                );
            }
        });
    }

    private void setupOrientationListener() {
        if (orientationEventListener != null) {
            return;
        }

        orientationEventListener = new OrientationEventListener(requireContext()) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }

                int newRotation = UseCase.snapToSurfaceRotation(orientation);

                if (newRotation == currentTargetRotation) {
                    return;
                }

                currentTargetRotation = newRotation;

                if (videoCapture != null) {
                    videoCapture.setTargetRotation(currentTargetRotation);
                }
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    private Bitmap rotateBitmapForFinalImage(Bitmap bitmap) {
        switch (currentTargetRotation) {
            case Surface.ROTATION_90:
                return rotateBitmap(bitmap, 270);

            case Surface.ROTATION_270:
                return rotateBitmap(bitmap, 90);

            case Surface.ROTATION_180:
                return rotateBitmap(bitmap, 180);

            case Surface.ROTATION_0:
            default:
                return bitmap;
        }
    }

    private Bitmap rotateBitmap(Bitmap bitmap, float degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );

        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }

        return rotatedBitmap;
    }

    public void startVideoCapture(int videoDurationMs, VideoCallback callback) {
        this.videoCallback = callback;
        this.videoResultSent = false;
        this.stopRequested = false;

        if (videoCapture == null) {
            notifyVideoError("VideoCapture is not initialized");
            return;
        }

        if (activeRecording != null) {
            notifyVideoError("Recording already in progress");
            return;
        }

        try {
            File videoDir = requireContext().getFilesDir();

            if (!videoDir.exists()) {
                videoDir.mkdirs();
            }

            File recordingFile = new File(
                    videoDir,
                    UUID.randomUUID().toString() + ".mp4"
            );

            boolean hasAudioPermission =
                    ContextCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED;

            PendingRecording pendingRecording =
                    videoCapture.getOutput()
                            .prepareRecording(
                                    requireContext(),
                                    new FileOutputOptions.Builder(recordingFile).build()
                            );

            if (hasAudioPermission) {
                pendingRecording = pendingRecording.withAudioEnabled();
            }

            activeRecording = pendingRecording.start(
                    ContextCompat.getMainExecutor(requireContext()),
                    videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            if (!videoResultSent && videoCallback != null) {
                                videoCallback.onStart();
                            }
                            return;
                        }

                        if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalizeEvent =
                                    (VideoRecordEvent.Finalize) videoRecordEvent;

                            activeRecording = null;
                            stopRequested = false; //

                            if (finalizeEvent.hasError()) {
                                notifyVideoError("Recording failed: " + finalizeEvent.getError());
                                return;
                            }

                            try {
                                File thumbnailFile = createVideoThumbnailFile(recordingFile);

                                String videoNativePath = Uri.fromFile(recordingFile).toString();
                                String thumbnailNativePath = Uri.fromFile(thumbnailFile).toString();

                                if (!videoResultSent && videoCallback != null) {
                                    videoResultSent = true;
                                    videoCallback.onStop(videoNativePath, thumbnailNativePath);
                                }

                            } catch (Exception e) {
                                notifyVideoError("Failed to create video thumbnail: " + e.getMessage());
                            }
                        }
                    }
            );

            if (videoDurationMs > 0) {
                final Recording recordingToStop = activeRecording;

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (activeRecording == recordingToStop && !stopRequested) {
                        stopVideoCapture();
                    }
                }, videoDurationMs);
            }

        } catch (Exception e) {
            notifyVideoError("startVideoCapture error: " + e.getMessage());
            stopVideoCapture();
        }
    }

    public void stopVideoCapture() {
        try {
            if (activeRecording == null || stopRequested) {
                return;
            }

            stopRequested = true;
            activeRecording.stop();

        } catch (Exception e) {
            notifyVideoError("stopVideoCapture failed: " + e.getMessage());
        }
    }

    public interface VideoCallback {
        void onStart();
        void onStop(String nativePath, String thumbnailNativePath);
        void onError(String error);
    }

    private File createVideoThumbnailFile(File videoFile) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(videoFile.getAbsolutePath());

            Bitmap bitmap = retriever.getFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );

            if (bitmap == null) {
                throw new Exception("Failed to create video thumbnail");
            }

            File thumbnailFile = new File(
                    videoFile.getParentFile(),
                    videoFile.getName().replace(".mp4", "_thumb.jpg")
            );

            OutputStream outputStream = new java.io.FileOutputStream(thumbnailFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
            outputStream.flush();
            outputStream.close();

            bitmap.recycle();

            return thumbnailFile;

        } finally {
            retriever.release();
        }
    }

    private void notifyVideoError(String error) {
        requireActivity().runOnUiThread(() -> {
            if (!videoResultSent && videoCallback != null) {
                videoResultSent = true;
                videoCallback.onError(error);
            }
        });
    }
}