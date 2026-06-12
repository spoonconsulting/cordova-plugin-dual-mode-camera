package com.spoon.dualcamera;

import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
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
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.cordova.CallbackContext;
import android.net.Uri;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.view.OrientationEventListener;
import androidx.camera.core.CompositionSettings;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.MirrorMode;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;

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

        setupOrientationListener();
        return root;
    }

    private PreviewView createPreviewView(){
        PreviewView previewView = new PreviewView(requireContext());
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        return previewView;
    }

    @Override
    public void onViewCreated( @NonNull View view,@Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        startCamera(enableCallback);
    }

    private void startCamera(final CallbackContext callbackContext) {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());

        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = future.get();
                    bindDualCamera();  //bind the front and back cameras
                    if (callbackContext != null) {
                        callbackContext.success();
                    }
                } catch (Exception e) {
                    if (callbackContext != null) {
                        callbackContext.error(e.getMessage());
                    }

                }
            }
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

    private void bindDualCamera() {
        try {
            cameraProvider.unbindAll();

            List<List<CameraInfo>> cameraCombinations =
                    cameraProvider.getAvailableConcurrentCameraInfos();

            CameraInfo selectedFrontCameraInfo = null;
            CameraInfo selectedBackCameraInfo = null;

            for (List<CameraInfo> combination : cameraCombinations) {
                CameraInfo frontInThisCombination = null;
                CameraInfo backInThisCombination = null;

                for (CameraInfo cameraInfo : combination) {
                    Integer lensFacing = cameraInfo.getLensFacing();

                    if (lensFacing == null) {
                        continue;
                    }

                    switch (lensFacing) {
                        case CameraSelector.LENS_FACING_FRONT:
                            frontInThisCombination = cameraInfo;
                            break;
                        case CameraSelector.LENS_FACING_BACK:
                            backInThisCombination = cameraInfo;
                            break;
                    }
                }

                if (frontInThisCombination != null && backInThisCombination != null) {
                    selectedFrontCameraInfo = frontInThisCombination;
                    selectedBackCameraInfo = backInThisCombination;
                    break;
                }
            }

            if (selectedFrontCameraInfo == null || selectedBackCameraInfo == null) {
                throw new IllegalStateException(
                        "Front/back concurrent camera is not available"
                );
            }

            ResolutionSelector resolutionSelector =
                    new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            )
                            .build();

            Preview preview  =
                    new Preview.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setTargetRotation(currentTargetRotation)
                            .build();

            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            videoCapture =
                    new VideoCapture.Builder<>(createRecorder())
                            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                            .build();

            videoCapture.setTargetRotation(currentTargetRotation);

            UseCaseGroup useCaseGroup =
                    new UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(videoCapture)
                            .build();

            CompositionSettings backFullScreen =
                    new CompositionSettings.Builder()
                            .setAlpha(1.0f)
                            .setOffset(0.0f, 0.0f)
                            .setScale(1.0f, 1.0f)
                            .build();

            float pipScale = frontWidthRatio;

            CompositionSettings frontPip =
                    new CompositionSettings.Builder()
                            .setAlpha(1.0f)
                            .setOffset(
                                    -1.0f + pipScale + frontMarginNdc,
                                    1.0f - pipScale - frontMarginNdc
                            )
                            .setScale(pipScale, pipScale)
                            .build();

            List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();

            configs.add(new ConcurrentCamera.SingleCameraConfig(
                            selectedBackCameraInfo.getCameraSelector(),
                            useCaseGroup,
                            backFullScreen,
                            this
                    ));

            configs.add(new ConcurrentCamera.SingleCameraConfig(
                            selectedFrontCameraInfo.getCameraSelector(),
                            useCaseGroup,
                            frontPip,
                            this
                    ));

            concurrentCamera = cameraProvider.bindToLifecycle(configs);

        } catch (Exception e) {
            videoCapture = null;
            concurrentCamera = null;

            if (enableCallback != null) {
                enableCallback.error(e.getMessage());
            }
        }
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

                final Bitmap finalBitmap = rotateBitmapIfNeeded(bitmap);

                final File finalFile = new File(
                        requireContext().getFilesDir(),
                        UUID.randomUUID().toString() + ".jpg"
                );

                new Thread(() -> {
                    try {
                        OutputStream outputStream =
                                new java.io.FileOutputStream(finalFile);

                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
                        outputStream.flush();
                        outputStream.close();

                        finalBitmap.recycle();

                        final String imageNativePath =
                                Uri.fromFile(finalFile).toString();

                        requireActivity().runOnUiThread(() ->
                                callbackContext.success(imageNativePath)
                        );

                    } catch (Exception e) {
                        bitmap.recycle();

                        requireActivity().runOnUiThread(() ->
                                callbackContext.error(e.getMessage())
                        );
                    }
                }).start();

            } catch (Exception e) {
                callbackContext.error(e.getMessage());
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

                currentTargetRotation = UseCase.snapToSurfaceRotation(orientation);

                if (videoCapture != null) {
                    videoCapture.setTargetRotation(currentTargetRotation);
                }
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    private Bitmap rotateBitmapIfNeeded(Bitmap bitmap) {
        if (currentTargetRotation == Surface.ROTATION_90) {
            return rotateBitmap(bitmap, 270);
        }

        if (currentTargetRotation == Surface.ROTATION_270) {
            return rotateBitmap(bitmap, 90);
        }

        if (currentTargetRotation == Surface.ROTATION_180) {
            return rotateBitmap(bitmap, 180);
        }

        return bitmap;
    }

    private Bitmap rotateBitmap(Bitmap bitmap, float degrees) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
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

}