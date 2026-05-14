package com.spoon.dualcamera;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;

import org.apache.cordova.CallbackContext;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import androidx.exifinterface.media.ExifInterface;
import android.graphics.Matrix;

public class DualCameraPreviewFragment extends Fragment {
    private static final String TAG = "DualCameraFragment"; //remove
    private PreviewView backPreviewView;
    private PreviewView frontPreviewView;
    private ProcessCameraProvider cameraProvider;
    private ConcurrentCamera concurrentCamera;
    private ImageCapture backImageCapture;
    private ImageCapture frontImageCapture;

    private CallbackContext enableCallback;

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

        backPreviewView = new PreviewView(requireContext());
        backPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        backPreviewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        root.addView(backPreviewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        frontPreviewView = new PreviewView(requireContext());
        frontPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        frontPreviewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int frontWidth = Math.round(screenWidth * 0.30f);
        int frontHeight = Math.round(frontWidth * 4f / 3f);

        FrameLayout.LayoutParams frontParams = new FrameLayout.LayoutParams(
                frontWidth,
                frontHeight
        );

        frontParams.gravity = Gravity.TOP | Gravity.END;
        frontParams.topMargin = dpToPx(16);
        frontParams.rightMargin = dpToPx(16);

        root.addView(frontPreviewView, frontParams);

        return root;
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
                        callbackContext.success("Dual camera preview enabled");
                    }
                } catch (Exception e) {
                    if (callbackContext != null) {
                        callbackContext.error(e.getMessage());
                    }

                }
            }
        }, ContextCompat.getMainExecutor(requireContext()));
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

                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        frontInThisCombination = cameraInfo;

                    } else if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        backInThisCombination = cameraInfo;
                    }
                }

                if (frontInThisCombination != null && backInThisCombination != null) {
                    selectedFrontCameraInfo = frontInThisCombination;
                    selectedBackCameraInfo = backInThisCombination;
                    break;
                }
            }

            if (selectedFrontCameraInfo == null || selectedBackCameraInfo == null) {
                return;
            }

            Preview backPreview = new Preview.Builder().build();
            backPreview.setSurfaceProvider(backPreviewView.getSurfaceProvider());

            backImageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            Preview frontPreview = new Preview.Builder().build();
            frontPreview.setSurfaceProvider(frontPreviewView.getSurfaceProvider());

            frontImageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            UseCaseGroup backUseCaseGroup = new UseCaseGroup.Builder()
                    .addUseCase(backPreview)
                    .addUseCase(backImageCapture)
                    .build();

            UseCaseGroup frontUseCaseGroup = new UseCaseGroup.Builder()
                    .addUseCase(frontPreview)
                    .addUseCase(frontImageCapture)
                    .build();

            List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();

            
            configs.add(new ConcurrentCamera.SingleCameraConfig(
                    selectedBackCameraInfo.getCameraSelector(),
                    backUseCaseGroup,
                    this
            ));

            configs.add(new ConcurrentCamera.SingleCameraConfig(
                    selectedFrontCameraInfo.getCameraSelector(),
                    frontUseCaseGroup,
                    this
            ));


            if (configs.size() != 2) {
                return;
            }

            concurrentCamera = cameraProvider.bindToLifecycle(configs);


        } catch (Exception e) {
            backImageCapture = null;
            frontImageCapture = null;
            concurrentCamera = null;
        }
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        concurrentCamera = null;
        backPreviewView = null;
        frontPreviewView = null;
    }

    public void capture(final CallbackContext callbackContext) {

        if (!isAdded() || getContext() == null) {
            callbackContext.error("Fragment is not attached");
            return;
        }

        if (backImageCapture == null || frontImageCapture == null) {
            callbackContext.error("ImageCapture is not ready");
            return;
        }

        try {
            final File filesDir = requireContext().getFilesDir();
    
            final File backTempFile = new File(
                    filesDir,
                    "back_" + UUID.randomUUID().toString() + ".jpg"
            );

            final File frontTempFile = new File(
                    filesDir,
                    "front_" + UUID.randomUUID().toString() + ".jpg"
            );

            final File finalFile = new File(
                    filesDir, UUID.randomUUID().toString() + ".jpg"
            );

            captureToFile(backImageCapture, backTempFile, new CaptureFileCallback() {
                @Override
                public void onSuccess(File backFile) {
                    captureToFile(frontImageCapture, frontTempFile, new CaptureFileCallback() {
                        @Override
                        public void onSuccess(File frontFile) {
                            new Thread(new Runnable() {
                                @Override
                                public void run(){
                                    try {
                                        mergeImages(backFile, frontFile, finalFile);

                                        backFile.delete();
                                        frontFile.delete();

                                        final String imageNativePath = finalFile.getAbsolutePath();

                                        requireActivity().runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                callbackContext.success(imageNativePath);
                                            }
                                        });

                                    } catch (Exception e) {
                                        requireActivity().runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                callbackContext.error(e.getMessage());
                                            }
                                        });
                                    }
                                }
                            }).start();
                        }
                        @Override
                        public void onError(String error) {
                            backFile.delete();
                            callbackContext.error("Front camera capture failed: " + error);
                        }
                    });
                }
                @Override
                public void onError(String error) {
                    callbackContext.error("Back camera capture failed: " + error);
                }
            });

        } catch (Exception e) {
            callbackContext.error(e.getMessage());
        }
    }

    private void mergeImages(File backFile, File frontFile, File finalFile) throws Exception {
        Bitmap backBitmap = decodeBitmapWithCorrectOrientation(backFile);
        Bitmap frontBitmap = decodeBitmapWithCorrectOrientation(frontFile);

        if (backBitmap == null || frontBitmap == null) {
            throw new Exception("Failed to decode captured images");
        }

        Bitmap resultBitmap = backBitmap.copy(Bitmap.Config.ARGB_8888, true);

        Canvas canvas = new Canvas(resultBitmap);

        int pipWidth = resultBitmap.getWidth() / 4;
        int pipHeight = (int) ((float) frontBitmap.getHeight() / frontBitmap.getWidth() * pipWidth);

        Bitmap resizedFrontBitmap = Bitmap.createScaledBitmap(
                frontBitmap,
                pipWidth,
                pipHeight,
                true
        );

        int margin = dpToPx(16);

        int left = resultBitmap.getWidth() - pipWidth - margin;
        int top = margin;

        canvas.drawBitmap(resizedFrontBitmap, left, top, null);

        OutputStream outputStream = new java.io.FileOutputStream(finalFile);
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
        outputStream.flush();
        outputStream.close();

        backBitmap.recycle();
        frontBitmap.recycle();
        resizedFrontBitmap.recycle();
        resultBitmap.recycle();
    }

    private interface CaptureFileCallback {
        void onSuccess(File file);
        void onError(String error);
    }

    private void captureToFile(ImageCapture capture, final File file, final CaptureFileCallback callback
    ) {
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(file).build();

        capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        callback.onSuccess(file);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        String message = exception.getMessage();

                        if (message == null) {
                            message = "Image capture failed";
                        }

                        callback.onError(message);
                    }
                }
        );
    }
    private Bitmap decodeBitmapWithCorrectOrientation(File file) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());

        if (bitmap == null) {
            throw new Exception("Failed to decode image: " + file.getAbsolutePath());
        }

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());

        int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
        );

        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;

            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;

            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;

            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.preScale(-1, 1);
                break;

            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.preScale(1, -1);
                break;

            default:
                return bitmap;
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );

        bitmap.recycle();

        return rotatedBitmap;
    }
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}

